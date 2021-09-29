package com.hedera.services.txns.contract.gascalculator;

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

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.Gas;
import org.hyperledger.besu.evm.gascalculator.PetersburgGasCalculator;

public class GasCalculatorHedera_0_19_0 extends PetersburgGasCalculator {
	@Override
	public Gas codeDepositGasCost(final int codeSize) {
		return Gas.ZERO;
	}

	@Override
	public Gas transactionIntrinsicGasCost(final Bytes payload, final boolean isContractCreate) {
		return Gas.ZERO;
	}
}
