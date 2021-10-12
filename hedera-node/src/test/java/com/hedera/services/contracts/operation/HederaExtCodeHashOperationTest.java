package com.hedera.services.contracts.operation;

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

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.Gas;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.FixedStack;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class HederaExtCodeHashOperationTest {

	@Mock
	private WorldUpdater worldUpdater;

	@Mock
	private Account account;

	@Mock
	private GasCalculator gasCalculator;

	@Mock
	private MessageFrame mf;

	@Mock
	private EVM evm;

	private HederaExtCodeHashOperation subject;

	final private String ETH_ADDRESS = "0xc257274276a4e539741ca11b590b9447b26a8051";
	final private Address ETH_ADDRESS_INSTANCE = Address.fromHexString(ETH_ADDRESS);
	final private Gas OPERATION_COST = Gas.of(1_000L);
	final private Gas WARM_READ_COST = Gas.of(100L);
	final private Gas ACTUAL_COST = OPERATION_COST.plus(WARM_READ_COST);

	@BeforeEach
	void setUp() {
		subject = new HederaExtCodeHashOperation(gasCalculator);
		given(gasCalculator.extCodeHashOperationGasCost()).willReturn(OPERATION_COST);
		given(gasCalculator.getWarmStorageReadCost()).willReturn(WARM_READ_COST);
	}

	@Test
	void executeResolvesToInvalidSolidityAddress() {
		given(mf.popStackItem()).willReturn(ETH_ADDRESS_INSTANCE);
		given(mf.getWorldUpdater()).willReturn(worldUpdater);

		var opResult = subject.execute(mf, evm);

		assertEquals(Optional.of(HederaExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS), opResult.getHaltReason());
		assertEquals(Optional.of(ACTUAL_COST), opResult.getGasCost());
	}

	@Test
	void executeResolvesToInsufficientGas() {
		givenMessageFrameWithRemainingGas(ACTUAL_COST.minus(Gas.of(1L)));

		var opResult = subject.execute(mf, evm);

		assertEquals(Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS), opResult.getHaltReason());
		assertEquals(Optional.of(ACTUAL_COST), opResult.getGasCost());
	}

	@Test
	void executeHappyPathWithEmptyAccount() {
		givenMessageFrameWithRemainingGas(ACTUAL_COST.plus(Gas.of(1L)));
		given(account.isEmpty()).willReturn(true);

		var opResult = subject.execute(mf, evm);

		assertEquals(Optional.of(ACTUAL_COST), opResult.getGasCost());
	}

	@Test
	void executeHappyPathWithAccount() {
		givenMessageFrameWithRemainingGas(ACTUAL_COST.plus(Gas.of(1L)));
		given(account.isEmpty()).willReturn(false);
		given(account.getCodeHash()).willReturn(Hash.hash(Bytes.of(1)));

		var opResult = subject.execute(mf, evm);

		assertEquals(Optional.of(ACTUAL_COST), opResult.getGasCost());
	}

	@Test
	void executeWithGasRemainingAsActualCost() {
		givenMessageFrameWithRemainingGas(ACTUAL_COST);
		given(account.isEmpty()).willReturn(false);
		given(account.getCodeHash()).willReturn(Hash.hash(Bytes.of(1)));

		var opResult = subject.execute(mf, evm);

		assertEquals(Optional.of(ACTUAL_COST), opResult.getGasCost());
	}

	@Test
	void executeThrowsInsufficientStackItems() {
		given(mf.popStackItem()).willThrow(FixedStack.UnderflowException.class);

		var opResult = subject.execute(mf, evm);

		assertEquals(Optional.of(INSUFFICIENT_STACK_ITEMS), opResult.getHaltReason());
		assertEquals(Optional.of(ACTUAL_COST), opResult.getGasCost());
	}

	private void givenMessageFrameWithRemainingGas(Gas gas) {
		given(mf.popStackItem()).willReturn(ETH_ADDRESS_INSTANCE);
		given(mf.getWorldUpdater()).willReturn(worldUpdater);
		given(mf.warmUpAddress(ETH_ADDRESS_INSTANCE)).willReturn(true);
		given(mf.getRemainingGas()).willReturn(gas);
		given(worldUpdater.get(ETH_ADDRESS_INSTANCE)).willReturn(account);
	}
}
