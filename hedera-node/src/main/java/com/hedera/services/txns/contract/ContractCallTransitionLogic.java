package com.hedera.services.txns.contract;

/*-
 * ‌
 * Hedera Services Node
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

import com.hedera.services.context.TransactionContext;
import com.hedera.services.contracts.execution.CallEvmTxProcessor;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.records.TransactionRecordService;
import com.hedera.services.store.AccountStore;
import com.hedera.services.store.contracts.HederaWorldState;
import com.hedera.services.store.models.Id;
import com.hedera.services.txns.TransitionLogic;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.swirlds.common.CommonUtils;
import org.apache.tuweni.bytes.Bytes;

import javax.inject.Inject;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_NEGATIVE_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_NEGATIVE_VALUE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

public class ContractCallTransitionLogic implements TransitionLogic {

	private final AccountStore accountStore;
	private final EntityIdSource ids;
	private final TransactionContext txnCtx;
	private final HederaWorldState worldState;
	private final TransactionRecordService recordService;
	private final CallEvmTxProcessor evmTxProcessor;

	private final Function<TransactionBody, ResponseCodeEnum> SEMANTIC_CHECK = this::validateSemantics;

	@Inject
	public ContractCallTransitionLogic(
			TransactionContext txnCtx,
			EntityIdSource ids,
			AccountStore accountStore,
			HederaWorldState worldState,
			TransactionRecordService recordService,
			CallEvmTxProcessor evmTxProcessor
	) {
		this.txnCtx = txnCtx;
		this.ids = ids;
		this.worldState = worldState;
		this.accountStore = accountStore;
		this.recordService = recordService;
		this.evmTxProcessor = evmTxProcessor;
	}

	@Override
	public void doStateTransition() {

		/* --- Translate from gRPC types --- */
		var contractCallTxn = txnCtx.accessor().getTxn();
		var op = contractCallTxn.getContractCall();
		final var senderId = Id.fromGrpcAccount(contractCallTxn.getTransactionID().getAccountID());
		final var contractId = Id.fromGrpcContract(op.getContractID());

		/* --- Load the model objects --- */
		final var sender = accountStore.loadAccount(senderId);
		final var receiver = accountStore.loadContract(contractId);
		final var callData = !op.getFunctionParameters().isEmpty()
				? Bytes.fromHexString(CommonUtils.hex(op.getFunctionParameters().toByteArray())) : Bytes.EMPTY;

		final var bytesReceiver = receiver.getId().asEvmAddress().toArray();

		/* --- Do the business logic --- */
		final var result = evmTxProcessor.execute(
				sender,
				receiver.getId().asEvmAddress(),
				op.getGas(),
				op.getAmount(),
				callData,
				txnCtx.consensusTime());

		/* --- Persist changes into state --- */
		final var createdContracts = worldState.persist();
		worldState.customizeSponsoredAccounts();
		result.setCreatedContracts(createdContracts);

		/* --- Externalise result --- */
		recordService.externaliseEvmCallTransaction(result);
	}

	@Override
	public void reclaimCreatedIds() {
		ids.reclaimProvisionalIds();
	}

	@Override
	public void resetCreatedIds() {
		ids.resetProvisionalIds();
	}

	@Override
	public Predicate<TransactionBody> applicability() {
		return TransactionBody::hasContractCall;
	}

	@Override
	public Function<TransactionBody, ResponseCodeEnum> semanticCheck() {
		return SEMANTIC_CHECK;
	}

	private ResponseCodeEnum validateSemantics(final TransactionBody transactionBody) {
		var op = transactionBody.getContractCall();

		if (op.getGas() < 0) {
			return CONTRACT_NEGATIVE_GAS;
		}
		if (op.getAmount() < 0) {
			return CONTRACT_NEGATIVE_VALUE;
		}
		return OK;
	}
}
