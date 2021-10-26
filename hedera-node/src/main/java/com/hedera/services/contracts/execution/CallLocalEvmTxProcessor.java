package com.hedera.services.contracts.execution;

/*
 * -
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 *
 */

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.exceptions.InvalidTransactionException;
import com.hedera.services.fees.HbarCentExchange;
import com.hedera.services.fees.calculation.UsagePricesProvider;
import com.hedera.services.store.contracts.CodeCache;
import com.hedera.services.store.contracts.HederaWorldState;
import com.hedera.services.store.models.Account;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Extension of the base {@link EvmTxProcessor} that provides interface for
 * executing {@link com.hederahashgraph.api.proto.java.ContractCallLocal} queries
 */
@Singleton
public class CallLocalEvmTxProcessor extends EvmTxProcessor {
	private static final Logger logger = LogManager.getLogger(CallLocalEvmTxProcessor.class);

	private CodeCache codeCache;

	@Inject
	public CallLocalEvmTxProcessor(
			HederaWorldState worldState,
			CodeCache codeCache,
			HbarCentExchange exchange,
			UsagePricesProvider usagePrices,
			GlobalDynamicProperties dynamicProperties,
			GasCalculator gasCalculator,
			Set<Operation> hederaOperations) {
		super(worldState, exchange, usagePrices, dynamicProperties, gasCalculator, hederaOperations);

		this.codeCache = codeCache;
	}

	@Override
	protected HederaFunctionality getFunctionType() {
		return HederaFunctionality.ContractCallLocal;
	}

	public TransactionProcessingResult execute(
			final Account sender,
			final Address receiver,
			final long providedGasLimit,
			final long value,
			final Bytes callData,
			final Instant consensusTime
	) {
		final long gasPrice = 1;

		return super.execute(sender,
				receiver,
				gasPrice,
				providedGasLimit,
				value,
				callData,
				false,
				consensusTime,
				true,
				Optional.empty());
	}

	@Override
	protected MessageFrame buildInitialFrame(MessageFrame.Builder baseInitialFrame, HederaWorldState.Updater updater, Address to, Bytes payload) {
		try {
			Code code = codeCache.get(to);
			return baseInitialFrame
					.type(MessageFrame.Type.MESSAGE_CALL)
					.address(to)
					.contract(to)
					.inputData(payload)
					.code(code)
					.build();
		} catch (ExecutionException e) {
			logger.warn("Error fetching code from cache", e.getCause());
			throw new InvalidTransactionException(ResponseCodeEnum.FAIL_INVALID);
		}
	}
}
