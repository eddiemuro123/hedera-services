package com.hedera.services.txns.contract.process;

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

import com.hedera.services.context.TransactionContext;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.contracts.execution.SoliditySigsVerifier;
import com.hedera.services.exceptions.InvalidTransactionException;
import com.hedera.services.fees.HbarCentExchange;
import com.hedera.services.fees.calculation.UsagePricesProvider;
import com.hedera.services.store.contracts.HederaWorldState;
import com.hedera.services.store.models.Account;
import com.hedera.services.store.models.Id;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Timestamp;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.Gas;
import org.hyperledger.besu.evm.account.EvmAccount;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.data.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Deque;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class CreateEvmTxProcessorTest {
	private static final int MAX_STACK_SIZE = 1024;

	@Mock
	private SoliditySigsVerifier sigsVerifier;
	@Mock
	private HederaWorldState worldState;
	@Mock
	private HbarCentExchange hbarCentExchange;
	@Mock
	private UsagePricesProvider usagePricesProvider;
	@Mock
	private GlobalDynamicProperties globalDynamicProperties;
	@Mock
	private Transaction transaction;
	@Mock
	private TransactionContext transactionContext;
	@Mock
	private HederaWorldState.Updater updater;
	@Mock
	private ExchangeRate exchangeRate;

	private CreateEvmTxProcessor createEvmTxProcessor;
	private final Account sender = new Account(new Id(0, 0, 1002));
	private final Account receiver = new Account(new Id(0, 0, 1006));
	private final Instant consensusTime = Instant.now();

	@BeforeEach
	private void setup() {
		createEvmTxProcessor = new CreateEvmTxProcessor(sigsVerifier, worldState, hbarCentExchange, usagePricesProvider, globalDynamicProperties);
	}

	@Test
	void assertSuccessExecutе() {
		givenValidMock();
		sender.initBalance(350_000L);
		var result = createEvmTxProcessor.execute(sender, receiver.getId().asEvmAddress(), 33_333L, 1234L, Bytes.EMPTY, consensusTime);
		assertTrue(result.isSuccessful());
		assertEquals(receiver.getId().asGrpcContract(), result.toGrpc().getContractID());
	}

	@Test
	void assertThatExecuteMethodThrowsInvalidTransactionException() {
		var consensusTime = Instant.ofEpochSecond(1631778674L);
		given(transactionContext.consensusTime()).willReturn(consensusTime);

		//expect:
		assertThrows(InvalidTransactionException.class, () ->
				createEvmTxProcessor.execute(sender, receiver.getId().asEvmAddress(), 1234, 1_000_000, 15, Bytes.EMPTY, false, transactionContext.consensusTime(), false));
	}

	@Test
	void assertIsContractCallFunctionality() {
		assertEquals(HederaFunctionality.ContractCreate, createEvmTxProcessor.getFunctionType());
	}

	@Test
	void assertTransactionSenderAndValue() {
		// setup:
		doReturn(Optional.of(receiver.getId().asEvmAddress())).when(transaction).getTo();
		given(transaction.getSender()).willReturn(sender.getId().asEvmAddress());
		given(transaction.getValue()).willReturn(Wei.of(1L));
		final MessageFrame.Builder commonInitialFrame =
				MessageFrame.builder()
						.messageFrameStack(mock(Deque.class))
						.maxStackSize(MAX_STACK_SIZE)
						.worldUpdater(mock(WorldUpdater.class))
						.initialGas(mock(Gas.class))
						.originator(sender.getId().asEvmAddress())
						.gasPrice(mock(Wei.class))
						.sender(sender.getId().asEvmAddress())
						.value(Wei.of(transaction.getValue().getAsBigInteger()))
						.apparentValue(Wei.of(transaction.getValue().getAsBigInteger()))
						.blockValues(mock(BlockValues.class))
						.depth(0)
						.completer(__ -> {
						})
						.miningBeneficiary(mock(Address.class))
						.blockHashLookup(h -> null);
		//when:
		MessageFrame buildMessageFrame = createEvmTxProcessor.buildInitialFrame(commonInitialFrame, worldState.updater(), (Address) transaction.getTo().get(), Bytes.EMPTY);

		//expect:
		assertEquals(transaction.getSender(), buildMessageFrame.getSenderAddress());
		assertEquals(transaction.getValue(), buildMessageFrame.getApparentValue());
	}

	private void givenValidMock() {
		given(worldState.updater()).willReturn(updater);
		given(worldState.updater().updater()).willReturn(updater);
		given(globalDynamicProperties.maxGas()).willReturn(10000000);
		given(globalDynamicProperties.fundingAccount()).willReturn(new Id(0, 0, 1010).asGrpcAccount());

		var evmAccount = mock(EvmAccount.class);

		given(updater.getOrCreateSenderAccount(sender.getId().asEvmAddress())).willReturn(evmAccount);
		given(updater.getOrCreateSenderAccount(sender.getId().asEvmAddress()).getMutable()).willReturn(mock(MutableAccount.class));
		given(worldState.updater()).willReturn(updater);


		var senderMutableAccount = mock(MutableAccount.class);
		given(senderMutableAccount.decrementBalance(any())).willReturn(Wei.of(1234L));
		given(senderMutableAccount.incrementBalance(any())).willReturn(Wei.of(1500L));
		given(senderMutableAccount.getNonce()).willReturn(0L);
		given(senderMutableAccount.getCode()).willReturn(Bytes.EMPTY);

		given(updater.getSenderAccount(any())).willReturn(evmAccount);
		given(updater.getSenderAccount(any()).getMutable()).willReturn(senderMutableAccount);
		given(updater.getOrCreate(any())).willReturn(evmAccount);
		given(updater.getOrCreate(any()).getMutable()).willReturn(senderMutableAccount);

		var feeData = mock(FeeData.class);
		given(feeData.getServicedata()).willReturn(mock(FeeComponents.class));
		given(usagePricesProvider.defaultPricesGiven(HederaFunctionality.ContractCreate, Timestamp.newBuilder().setSeconds(consensusTime.getEpochSecond()).build())).willReturn(feeData);
		given(hbarCentExchange.rate(Timestamp.newBuilder().setSeconds(consensusTime.getEpochSecond()).build())).willReturn(exchangeRate);
		given(exchangeRate.getHbarEquiv()).willReturn(1);
		given(exchangeRate.getCentEquiv()).willReturn(1);

		given(updater.getSenderAccount(any())).willReturn(evmAccount);
		given(updater.getSenderAccount(any()).getMutable()).willReturn(senderMutableAccount);
		given(updater.updater().getOrCreate(any()).getMutable()).willReturn(senderMutableAccount);

	}
}