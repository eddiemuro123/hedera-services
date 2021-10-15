package com.hedera.services.contracts.gascalculator;

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
import com.hedera.services.contracts.execution.HederaBlockValues;
import com.hedera.services.fees.HbarCentExchange;
import com.hedera.services.fees.calculation.UsagePricesProvider;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Timestamp;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.Gas;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GasCalculatorHederaV18Test {

	@Mock
	private GlobalDynamicProperties properties;
	@Mock
	private HbarCentExchange hbarCentExchange;
	@Mock
	private UsagePricesProvider usagePricesProvider;

	private GasCalculatorHederaV18 gasCalculatorHedera;

	@BeforeEach
	private void setup() {
		gasCalculatorHedera = new GasCalculatorHederaV18(properties, usagePricesProvider, hbarCentExchange);
	}

	@Test
	void assertAndVerifyLogOperationGasCost() {
		final var messageFrame = mock(MessageFrame.class);
		final var consensusTime = 123L;
		final var functionality = HederaFunctionality.ContractCreate;
		final var timestamp = Timestamp.newBuilder().setSeconds(consensusTime).build();
		final var returningDeque = new ArrayDeque<MessageFrame>() {
		};
		returningDeque.add(messageFrame);

		final var rbh = 20000L;
		final var feeComponents = FeeComponents.newBuilder().setRbh(rbh);
		final var feeData = FeeData.newBuilder().setServicedata(feeComponents).build();

		given(messageFrame.getGasPrice()).willReturn(Wei.of(2000L));
		given(messageFrame.getBlockValues()).willReturn(new HederaBlockValues(10L, consensusTime));
		given(messageFrame.getContextVariable("HederaFunctionality")).willReturn(functionality);
		given(messageFrame.getMessageFrameStack()).willReturn(returningDeque);

		given(usagePricesProvider.defaultPricesGiven(functionality, timestamp)).willReturn(feeData);
		given(hbarCentExchange.rate(timestamp)).willReturn(ExchangeRate.newBuilder().setHbarEquiv(2000).setCentEquiv(200).build());
		given(properties.cacheRecordsTtl()).willReturn(1000000);
		assertEquals(Gas.of(28), gasCalculatorHedera.logOperationGasCost(messageFrame, 1L, 2L, 3));
		verify(messageFrame).getGasPrice();
		verify(messageFrame).getBlockValues();
		verify(messageFrame).getContextVariable("HederaFunctionality");
		verify(messageFrame).getMessageFrameStack();
		verify(usagePricesProvider).defaultPricesGiven(functionality, timestamp);
		verify(hbarCentExchange).rate(timestamp);
	}

	@Test
	void assertCodeDepositGasCostIsZero() {
		assertEquals(Gas.ZERO, gasCalculatorHedera.codeDepositGasCost(10));
	}

	@Test
	void assertTransactionIntrinsicGasCost() {
		assertEquals(Gas.ZERO, gasCalculatorHedera.transactionIntrinsicGasCost(Bytes.EMPTY, true));
	}

	@Test
	void assertGetBalanceOperationGasCost() {
		assertEquals(Gas.of(20L), gasCalculatorHedera.getBalanceOperationGasCost());
	}

	@Test
	void assertExpOperationByteGasCost() {
		assertEquals(Gas.of(10L), gasCalculatorHedera.expOperationByteGasCost());
	}

	@Test
	void assertExtCodeBaseGasCost() {
		assertEquals(Gas.of(20L), gasCalculatorHedera.extCodeBaseGasCost());
	}

	@Test
	void assertCallOperationBaseGasCost() {
		assertEquals(Gas.of(40L), gasCalculatorHedera.callOperationBaseGasCost());
	}

	@Test
	void assertGetExtCodeSizeOperationGasCost() {
		assertEquals(Gas.of(20L), gasCalculatorHedera.getExtCodeSizeOperationGasCost());
	}

	@Test
	void assertExtCodeHashOperationGasCost() {
		assertEquals(Gas.of(400L), gasCalculatorHedera.extCodeHashOperationGasCost());
	}

	@Test
	void assertSelfDestructOperationGasCost() {
		Account recipient = mock(Account.class);
		assertEquals(Gas.of(0), gasCalculatorHedera.selfDestructOperationGasCost(recipient, Wei.of(10L)));
	}

	@Test
	void assertGetSloadOperationGasCost() {
		assertEquals(Gas.of(50L), gasCalculatorHedera.getSloadOperationGasCost());
	}
}
