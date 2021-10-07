package com.hedera.services.contracts.operation;

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

import com.hedera.services.contracts.sources.SoliditySigsVerifier;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.utils.EntityIdUtils;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.CallOperation;

import javax.inject.Inject;
import java.util.Optional;
import java.util.Set;

/**
 * Hedera adapted version of the {@link CallOperation}.
 *
 * Performs an existence check on the {@link Address} to be called
 * Halts the execution of the EVM transaction with {@link HederaExceptionalHaltReason#INVALID_SOLIDITY_ADDRESS} if
 * the account does not exist or it is deleted.
 *
 * If the target {@link Address} has {@link MerkleAccount#isReceiverSigRequired()} set to true, verification of the
 * provided signature is performed. If the signature is not
 * active, the execution is halted with {@link HederaExceptionalHaltReason#INVALID_SIGNATURE}.
 */
public class HederaCallOperation extends CallOperation {
	private final SoliditySigsVerifier sigsVerifier;

	@Inject
	public HederaCallOperation(
			SoliditySigsVerifier sigsVerifier,
			GasCalculator gasCalculator) {
		super(gasCalculator);
		this.sigsVerifier = sigsVerifier;
	}

	@Override
	public OperationResult execute(MessageFrame frame, EVM evm) {
		final var account = frame.getWorldUpdater().get(to(frame));
		if (account == null) {
			return new OperationResult(
					Optional.of(cost(frame)), Optional.of(HederaExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS));
		}

		final var accountId = EntityIdUtils.accountParsedFromSolidityAddress(account.getAddress().toArray());
		if (!sigsVerifier.allRequiredKeysAreActive(Set.of(accountId))) {
			return new OperationResult(
					Optional.of(cost(frame)), Optional.of(HederaExceptionalHaltReason.INVALID_SIGNATURE)
			);
		}

		return super.execute(frame, evm);
	}
}
