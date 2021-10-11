package com.hedera.services.bdd.spec.transactions.contract;

/*-
 * ‌
 * Hedera Services Test Clients
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
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
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.infrastructure.meta.ActionableContractCall;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ContractCallTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import org.ethereum.core.CallTransaction;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.extractTxnId;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.suites.HapiApiSuite.GENESIS;

public class HapiContractCall extends HapiTxnOp<HapiContractCall> {
	private static final String FALLBACK_ABI = "<empty>";

	private Object[] params;
	private String abi;
	private String contract;
	private Optional<Long> gas = Optional.empty();
	private Optional<Long> sentTinyHbars = Optional.of(0L);
	private Optional<String> details = Optional.empty();
	private Optional<Function<HapiApiSpec, Object[]>> paramsFn = Optional.empty();
	private Optional<LongConsumer> gasObserver = Optional.empty();

	@Override
	public HederaFunctionality type() {
		return HederaFunctionality.ContractCall;
	}

	@Override
	protected HapiContractCall self() {
		return this;
	}

	public static HapiContractCall fromDetails(String actionable) {
		HapiContractCall call = new HapiContractCall();
		call.details = Optional.of(actionable);
		return call;
	}
	private HapiContractCall() { }

	public HapiContractCall(String contract) {
		this.abi = FALLBACK_ABI;
		this.params = new Object[0];
		this.contract = contract;
	}

	public HapiContractCall(String abi, String contract, Object... params) {
		this.abi = abi;
		this.params = params;
		this.contract = contract;
	}

	public HapiContractCall(String abi, String contract, Function<HapiApiSpec, Object[]> fn) {
		this(abi, contract);
		paramsFn = Optional.of(fn);
	}

	public HapiContractCall exposingGasTo(LongConsumer gasObserver) {
		this.gasObserver = Optional.of(gasObserver);
		return this;
	}

	public HapiContractCall gas(long amount) {
		gas = Optional.of(amount);
		return this;
	}

	public HapiContractCall sending(long amount) {
		sentTinyHbars = Optional.of(amount);
		return this;
	}

	@Override
	protected Function<Transaction, TransactionResponse> callToUse(HapiApiSpec spec) {
		return spec.clients().getScSvcStub(targetNodeFor(spec), useTls)::contractCallMethod;
	}

	@Override
	protected long feeFor(HapiApiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
		return spec.fees().forActivityBasedOp(HederaFunctionality.ContractCall,
				scFees::getContractCallTxFeeMatrices, txn, numPayerKeys);
	}

	@Override
	protected Consumer<TransactionBody.Builder> opBodyDef(HapiApiSpec spec) throws Throwable {
		if (details.isPresent()) {
			ActionableContractCall actionable = spec.registry().getActionableCall(details.get());
			contract = actionable.getContract();
			abi = actionable.getDetails().getAbi();
			params = actionable.getDetails().getExampleArgs();
		} else if (paramsFn.isPresent()) {
			params = paramsFn.get().apply(spec);
		}

		byte[] callData = (abi != FALLBACK_ABI)
				? CallTransaction.Function.fromJsonInterface(abi).encode(params) : new byte[] {};

		ContractCallTransactionBody opBody = spec
				.txns()
				.<ContractCallTransactionBody, ContractCallTransactionBody.Builder>body(
						ContractCallTransactionBody.class, builder -> {
							builder.setContractID(spec.registry().getContractId(contract));
							builder.setFunctionParameters(ByteString.copyFrom(callData));
							sentTinyHbars.ifPresent(a -> builder.setAmount(a));
							gas.ifPresent(a -> builder.setGas(a));
						}
				);
		return b -> b.setContractCall(opBody);
	}

	@Override
	protected void updateStateOf(HapiApiSpec spec) throws Throwable {
		if (gasObserver.isPresent()) {
			final var txnId = extractTxnId(txnSubmitted);
			final var gasLookup = getTxnRecord(txnId)
					.assertingNothing()
					.noLogging()
					.payingWith(GENESIS)
					.nodePayment(1)
					.exposingTo(record -> {
						final var gasUsed = record.getContractCallResult().getGasUsed();
						System.out.println(gasUsed);
						gasObserver.get().accept(gasUsed);
					});
			allRunFor(spec, gasLookup);
		}
	}

	@Override
	protected MoreObjects.ToStringHelper toStringHelper() {
		return super.toStringHelper()
				.add("contract", contract)
				.add("abi", abi)
				.add("params", Arrays.toString(params));
	}
}
