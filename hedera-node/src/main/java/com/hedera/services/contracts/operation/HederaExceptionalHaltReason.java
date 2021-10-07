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

import com.hedera.services.state.merkle.MerkleAccount;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;

/**
 * Hedera adapted {@link ExceptionalHaltReason}
 */
public class HederaExceptionalHaltReason {

	/**
	 * Used when the EVM transaction accesses address that does not map to any existing (non-deleted)
	 * account
	 */
	public static ExceptionalHaltReason INVALID_SOLIDITY_ADDRESS = HederaExceptionalHalt.INVALID_SOLIDITY_ADDRESS;
	/**
	 * Used when {@link HederaSelfDestructOperation} is used and the beneficiary is specified to be the same as the
	 * destructed account
	 */
	public static ExceptionalHaltReason SELF_DESTRUCT_TO_SELF = HederaExceptionalHalt.SELF_DESTRUCT_TO_SELF;
	/**
	 * Used when there is no active signature for a given {@link com.hedera.services.state.merkle.MerkleAccount} that
	 * has {@link MerkleAccount#isReceiverSigRequired()} enabled and the account receives HBars
	 */
	public static ExceptionalHaltReason INVALID_SIGNATURE = HederaExceptionalHalt.INVALID_SIGNATURE;

	enum HederaExceptionalHalt implements ExceptionalHaltReason {
		INVALID_SOLIDITY_ADDRESS("Invalid account reference"),
		SELF_DESTRUCT_TO_SELF("Self destruct to the same address"),
		INVALID_SIGNATURE("Invalid signature");

		String description;

		HederaExceptionalHalt(final String description) {
			this.description = description;
		}

		@Override
		public String getDescription() {
			return description;
		}
	}

}
