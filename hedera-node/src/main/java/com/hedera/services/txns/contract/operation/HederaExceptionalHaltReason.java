package com.hedera.services.txns.contract.operation;

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

import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;

public class HederaExceptionalHaltReason {

	public static ExceptionalHaltReason INVALID_SOLIDITY_ADDRESS = HederaExceptionalHalt.INVALID_SOLIDITY_ADDRESS;
	public static ExceptionalHaltReason SELF_DESTRUCT_TO_SELF = HederaExceptionalHalt.SELF_DESTRUCT_TO_SELF;

	enum HederaExceptionalHalt implements ExceptionalHaltReason {
		INVALID_SOLIDITY_ADDRESS("Invalid account reference"),
		SELF_DESTRUCT_TO_SELF("Self destruct to the same address");

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
