package com.hedera.services.fees.calculation;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.context.primitives.StateView;
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.fees.FeeCalculator;
import com.hedera.services.fees.HbarCentExchange;
import com.hedera.services.keys.HederaKeyTraversal;
import com.hedera.services.utils.SignedTxnAccessor;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.fee.FeeBuilder;
import com.hederahashgraph.fee.FeeObject;
import com.hederahashgraph.fee.SigValueObj;
import com.hedera.services.legacy.core.jproto.JKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.hedera.services.fees.calculation.AwareFcfsUsagePrices.DEFAULT_USAGE_PRICES;
import static com.hederahashgraph.fee.FeeBuilder.getTinybarsFromTinyCents;
import static com.hederahashgraph.fee.FeeBuilder.getTransactionRecordFeeInTinyCents;

/**
 * Implements a {@link FeeCalculator} in terms of injected usage prices,
 * exchange rates, and collections of estimators which can infer the
 * resource usage of various transactions and queries.
 *
 * @author Michael Tinker
 */
public class UsageBasedFeeCalculator implements FeeCalculator {
	private static final Logger log = LogManager.getLogger(UsageBasedFeeCalculator.class);

	private final PropertySource properties;
	private final HbarCentExchange exchange;
	private final UsagePricesProvider usagePrices;
	private final List<QueryResourceUsageEstimator> queryUsageEstimators;
	private final Function<HederaFunctionality, List<TxnResourceUsageEstimator>> txnUsageEstimators;

	public UsageBasedFeeCalculator(
			PropertySource properties,
			HbarCentExchange exchange,
			UsagePricesProvider usagePrices,
			List<QueryResourceUsageEstimator> queryUsageEstimators,
			Function<HederaFunctionality, List<TxnResourceUsageEstimator>> txnUsageEstimators
	) {
		this.exchange = exchange;
		this.properties = properties;
		this.usagePrices = usagePrices;
		this.queryUsageEstimators = queryUsageEstimators;
		this.txnUsageEstimators = txnUsageEstimators;
	}

	@Override
	public void init() {
		usagePrices.loadPriceSchedules();
	}

	@Override
	public long computeCachingFee(TransactionRecord record) {
		return priceForStorage(record, properties.getIntProperty("cache.records.ttl"));
	}

	@Override
	public long computeStorageFee(TransactionRecord record) {
		return priceForStorage(record, properties.getIntProperty("ledger.records.ttl"));
	}

	private long priceForStorage(TransactionRecord record, int ttl) {
		long rbhPrice = usagePrices.activePrices().getServicedata().getRbh();
		long feeInTinyCents = getTransactionRecordFeeInTinyCents(record, rbhPrice, ttl);
		return getTinybarsFromTinyCents(exchange.activeRate(), feeInTinyCents);
	}

	@Override
	public FeeObject computePayment(
			Query query,
			FeeData usagePrices,
			StateView view,
			Timestamp at,
			Map<String, Object> queryCtx
	) {
		return compute(query, usagePrices, at, estimator -> estimator.usageGiven(query, view, queryCtx));
	}

	@Override
	public FeeObject estimatePayment(
			Query query,
			FeeData usagePrices,
			StateView view,
			Timestamp at,
			ResponseType type
	) {
		return compute(query, usagePrices, at, estimator -> estimator.usageGivenType(query, view, type));
	}

	private FeeObject compute(
			Query query,
			FeeData usagePrices,
			Timestamp at,
			Function<QueryResourceUsageEstimator, FeeData> usageFn
	) {
		try {
			var usageEstimator = getQueryUsageEstimator(query);
			var queryUsage = usageFn.apply(usageEstimator);
			return FeeBuilder.getFeeObject(usagePrices, queryUsage, exchange.rate(at));
		} catch (Exception illegal) {
			log.warn("Unexpected failure for {}!", query, illegal);
			throw new IllegalArgumentException("UsageBasedFeeCalculator#computeGiven");
		}
	}

	@Override
	public FeeObject computeFee(SignedTxnAccessor accessor, JKey payerKey, StateView view) {
		return feeGiven(accessor, payerKey, view, usagePrices.activePrices(), exchange.activeRate());
	}

	@Override
	public FeeObject estimateFee(SignedTxnAccessor accessor, JKey payerKey, StateView view, Timestamp at) {
		FeeData prices = uncheckedPricesGiven(accessor, at);

		return feeGiven(accessor, payerKey, view, prices, exchange.rate(at));
	}

	private FeeData uncheckedPricesGiven(SignedTxnAccessor accessor, Timestamp at) {
		try {
			return usagePrices.pricesGiven(accessor.getFunction(), at);
		} catch (Exception e) {
			log.warn("Using default usage prices to calculate fees for {}!", accessor.getSignedTxn4Log(), e);
		}
		return DEFAULT_USAGE_PRICES;
	}

	private FeeObject feeGiven(
			SignedTxnAccessor accessor,
			JKey payerKey,
			StateView view,
			FeeData prices,
			ExchangeRate rate
	) {
		try {
			var sigUsage = getSigUsage(accessor, payerKey);
			var usageEstimator = getTxnUsageEstimator(accessor);
			var metrics = usageEstimator.usageGiven(accessor.getTxn(), sigUsage, view);
			return FeeBuilder.getFeeObject(prices, metrics, rate);
		} catch (Exception illegal) {
			var msg = String.format("Unable to compute fee for %s, key %s!", accessor.getSignedTxn4Log(), payerKey);
			throw new IllegalArgumentException(msg, illegal);
		}
	}

	private QueryResourceUsageEstimator getQueryUsageEstimator(Query query) {
		Optional<QueryResourceUsageEstimator> usageEstimator = queryUsageEstimators
				.stream()
				.filter(estimator -> estimator.applicableTo(query))
				.findAny();
		if (usageEstimator.isPresent()) {
			return usageEstimator.get();
		}
		throw new IllegalArgumentException("Missing query usage estimator!");
	}

	private TxnResourceUsageEstimator getTxnUsageEstimator(SignedTxnAccessor accessor) {
		var usageEstimator = Optional.ofNullable(txnUsageEstimators.apply(accessor.getFunction()))
				.map(estimators -> from(estimators, accessor.getTxn()));
		if (usageEstimator.isPresent()) {
			return usageEstimator.get();
		}
		throw new IllegalArgumentException("Missing txn usage estimator!");
	}

	private TxnResourceUsageEstimator from(List<TxnResourceUsageEstimator> estimators, TransactionBody txn) {
		for (TxnResourceUsageEstimator candidate : estimators) {
			if (candidate.applicableTo(txn)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("Missing txn usage estimator!");
	}

	private SigValueObj getSigUsage(SignedTxnAccessor accessor, JKey payerKey) {
		return new SigValueObj(
				FeeBuilder.getSignatureCount(accessor.getSignedTxn()),
				HederaKeyTraversal.numSimpleKeys(payerKey),
				FeeBuilder.getSignatureSize(accessor.getSignedTxn()));
	}
}
