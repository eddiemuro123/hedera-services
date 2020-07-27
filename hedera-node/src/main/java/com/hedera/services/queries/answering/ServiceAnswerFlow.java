package com.hedera.services.queries.answering;

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
import com.hedera.services.fees.FeeCalculator;
import com.hedera.services.fees.calculation.UsagePricesProvider;
import com.hedera.services.queries.AnswerFlow;
import com.hedera.services.queries.AnswerService;
import com.hedera.services.throttling.FunctionalityThrottling;
import com.hedera.services.utils.SignedTxnAccessor;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.fee.FeeObject;
import com.hedera.services.legacy.handler.TransactionHandler;
import com.swirlds.common.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PLATFORM_TRANSACTION_NOT_CREATED;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_ONLY;
import static com.hedera.services.legacy.handler.TransactionHandler.IS_THROTTLE_EXEMPT;

public class ServiceAnswerFlow implements AnswerFlow {
	private static final Logger log = LogManager.getLogger(ServiceAnswerFlow.class);

	SignedTxnAccessor defaultAccessor = null;

	private final Platform platform;
	private final FeeCalculator fees;
	private final TransactionHandler legacyHandler;
	private final Supplier<StateView> stateViews;
	private final UsagePricesProvider resourceCosts;
	private final FunctionalityThrottling throttles;

	public ServiceAnswerFlow(
			Platform platform,
			FeeCalculator fees,
			TransactionHandler legacyHandler,
			Supplier<StateView> stateViews,
			UsagePricesProvider resourceCosts,
			FunctionalityThrottling throttles
	) {
		this.fees = fees;
		this.platform = platform;
		this.throttles = throttles;
		this.stateViews = stateViews;
		this.legacyHandler = legacyHandler;
		this.resourceCosts = resourceCosts;

		try {
			defaultAccessor = new SignedTxnAccessor(Transaction.getDefaultInstance());
		} catch (Exception impossible) {
			log.warn("Impossible for this exception to be thrown : {}", impossible.getMessage());
		}
	}

	@Override
	public Response satisfyUsing(AnswerService service, Query query) {
		StateView view = stateViews.get();
		SignedTxnAccessor accessor = service.extractPaymentFrom(query).orElse(defaultAccessor);

		if (shouldThrottle(service, accessor)) {
			return service.responseGiven(query, view, BUSY);
		}

		ResponseCodeEnum validity = legacyHandler.validateQuery(query, service.requiresNodePayment(query));
		if (validity == OK) {
			validity = service.checkValidity(query, view);
		}
		if (validity != OK) {
			return service.responseGiven(query, view, validity);
		}

		Timestamp at = accessor.getTxnId().getTransactionValidStart();
		FeeData usagePrices = resourceCosts.pricesGiven(service.canonicalFunction(), at);

		long cost = 0L;
		if (service.requiresNodePayment(query)) {
			cost = computeTotalCost(query, usagePrices, view, at, null);
			validity = validatePayment(cost, accessor);
			if (validity != OK) {
				return service.responseGiven(query, view, validity, cost);
			}
			if (!legacyHandler.submitTransaction(platform, accessor.getSignedTxn(), accessor.getTxnId())) {
				return service.responseGiven(query, view, PLATFORM_TRANSACTION_NOT_CREATED, cost);
			}
		}

		if (service.needsAnswerOnlyCost(query)) {
			cost = computeTotalCost(query, usagePrices, view, at, ANSWER_ONLY);
		}

		return service.responseGiven(query, view, OK, cost);
	}

	private boolean shouldThrottle(AnswerService service, SignedTxnAccessor paymentAccessor) {
		if (IS_THROTTLE_EXEMPT.test(paymentAccessor.getPayer())) {
			return false;
		} else {
			return throttles.shouldThrottle(service.canonicalFunction());
		}
	}

	private long computeTotalCost(
			Query query,
			FeeData usagePrices,
			StateView view,
			Timestamp at,
			ResponseType type
	) {
		FeeObject costs = type != null
				? fees.estimatePayment(query, usagePrices, view, at, type)
				: fees.computePayment(query, usagePrices, view, at);
		return costs.getNetworkFee() + costs.getServiceFee() + costs.getNodeFee();
	}

	private ResponseCodeEnum validatePayment(long requiredPayment, SignedTxnAccessor accessor) {
		if (requiredPayment > 0) {
			ResponseCodeEnum validity =
					legacyHandler.validateTransactionPreConsensus(accessor.getSignedTxn(), true)
							.getValidity();
			if (validity == OK) {
				validity = legacyHandler.nodePaymentValidity(accessor.getSignedTxn(), requiredPayment);
			}
			return validity;
		} else {
			return OK;
		}
	}
}
