package com.hedera.services.bdd.spec.transactions.schedule;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.infrastructure.RegistryNotFound;
import com.hedera.services.bdd.spec.keys.SigMapGenerator;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.usage.schedule.ScheduleCreateUsage;
import com.hedera.services.usage.schedule.ScheduleSignUsage;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ScheduleSignTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.fee.SigValueObj;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.withNature;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asScheduleId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleSign;
import static java.util.stream.Collectors.toList;

public class HapiScheduleSign extends HapiTxnOp<HapiScheduleSign> {
	private static final Logger log = LogManager.getLogger(HapiScheduleSign.class);

	private static final int defaultScheduleTxnExpiry = HapiSpecSetup.getDefaultNodeProps()
			.getInteger("ledger.schedule.txExpiryTimeSecs");

	private final String schedule;
	private List<String> signatories = Collections.emptyList();

	public HapiScheduleSign(String schedule) {
		this.schedule = schedule;
	}

	public HapiScheduleSign withSignatories(String... keys)	 {
		signatories = List.of(keys);
		return this;
	}

	@Override
	protected HapiScheduleSign self() {
		return this;
	}

	@Override
	public HederaFunctionality type() {
		return ScheduleSign;
	}

	@Override
	protected Consumer<TransactionBody.Builder> opBodyDef(HapiApiSpec spec) throws Throwable {
		var registry = spec.registry();
		byte[] bytesToSign;

		try {
			bytesToSign = registry.getBytes(HapiScheduleCreate.registryBytesTag(schedule));
		} catch (RegistryNotFound rnf) {
			bytesToSign = new byte[] {};
		}

		var signingKeys = signatories.stream().map(k -> registry.getKey(k)).collect(toList());
		var authors = spec.keys().authorsFor(signingKeys, Collections.emptyMap());

		var ceremony = spec.keys().new Ed25519Signing(bytesToSign, authors);
		var sigs = ceremony.completed();
		ScheduleSignTransactionBody opBody = spec
				.txns()
				.<ScheduleSignTransactionBody, ScheduleSignTransactionBody.Builder>body(
						ScheduleSignTransactionBody.class, b -> {
							b.setScheduleID(asScheduleId(schedule, spec));
							b.setSigMap(withNature(SigMapGenerator.Nature.UNIQUE).forEd25519Sigs(sigs));
						}
				);
		return b -> b.setScheduleSign(opBody);
	}

	@Override
	protected Function<Transaction, TransactionResponse> callToUse(HapiApiSpec spec) {
		return spec.clients().getScheduleSvcStub(targetNodeFor(spec), useTls)::signSchedule;
	}

	@Override
	protected long feeFor(HapiApiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
		return spec.fees().forActivityBasedOp(
				HederaFunctionality.ScheduleSign, this::usageEstimate, txn, numPayerKeys);
	}

	private FeeData usageEstimate(TransactionBody txn, SigValueObj svo) {
		return ScheduleSignUsage.newEstimate(txn, suFrom(svo))
				.givenScheduledTxExpirationTimeSecs(defaultScheduleTxnExpiry)
				.get();
	}

	@Override
	protected MoreObjects.ToStringHelper toStringHelper() {
		MoreObjects.ToStringHelper helper = super.toStringHelper()
				.add("schedule", schedule);
		return helper;
	}
}
