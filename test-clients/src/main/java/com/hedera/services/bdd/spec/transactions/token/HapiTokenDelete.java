package com.hedera.services.bdd.spec.transactions.token;

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
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenCreation;
import com.hederahashgraph.api.proto.java.TokenDeletion;
import com.hederahashgraph.api.proto.java.TokenFreeze;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import com.hederahashgraph.fee.SigValueObj;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.netOf;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

public class HapiTokenDelete extends HapiTxnOp<HapiTokenDelete> {
	static final Logger log = LogManager.getLogger(HapiTokenDelete.class);

	private String token;

	@Override
	public HederaFunctionality type() {
		return HederaFunctionality.TokenDelete;
	}

	public HapiTokenDelete(String token) {
		this.token = token;
	}

	@Override
	protected HapiTokenDelete self() {
		return this;
	}

	@Override
	protected long feeFor(HapiApiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
		return spec.fees().forActivityBasedOp(
				HederaFunctionality.TokenDelete, this::mockTokenDeleteUsage, txn, numPayerKeys);
	}

	private FeeData mockTokenDeleteUsage(TransactionBody ignoredTxn, SigValueObj ignoredSigUsage) {
		return TxnUtils.defaultPartitioning(
				FeeComponents.newBuilder()
						.setMin(1)
						.setMax(1_000_000)
						.setConstant(5)
						.setBpt(5)
						.setVpt(5)
						.setRbh(5)
						.setSbh(5)
						.setGas(5)
						.setTv(5)
						.setBpr(5)
						.setSbpr(5)
						.build(), 5);
	}

	@Override
	protected Consumer<TransactionBody.Builder> opBodyDef(HapiApiSpec spec) throws Throwable {
		var tId = TxnUtils.asTokenId(token, spec);
		TokenDeletion opBody = spec
				.txns()
				.<TokenDeletion, TokenDeletion.Builder>body(
						TokenDeletion.class, b -> {
							b.setToken(TxnUtils.asRef(tId));
						});
		return b -> b.setTokenDeletion(opBody);
	}

	@Override
	protected List<Function<HapiApiSpec, Key>> defaultSigners() {
		return List.of(
				spec -> spec.registry().getKey(effectivePayer(spec)),
				spec -> spec.registry().getAdminKey(token));
	}

	@Override
	protected Function<Transaction, TransactionResponse> callToUse(HapiApiSpec spec) {
		return spec.clients().getTokenSvcStub(targetNodeFor(spec), useTls)::deleteToken;
	}

	@Override
	protected void updateStateOf(HapiApiSpec spec) {
	}

	@Override
	protected MoreObjects.ToStringHelper toStringHelper() {
		MoreObjects.ToStringHelper helper = super.toStringHelper()
				.add("token", token);
		return helper;
	}
}
