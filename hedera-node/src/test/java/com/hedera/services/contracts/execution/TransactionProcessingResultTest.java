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

import com.google.protobuf.ByteString;
import com.hedera.services.store.models.Account;
import com.hedera.services.store.models.Id;
import com.hedera.services.utils.EntityIdUtils;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.log.LogTopic;
import org.hyperledger.besu.evm.log.LogsBloomFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class TransactionProcessingResultTest {

	private static final long GAS_USAGE = 1234L;
	private static final long GAS_PRICE = 1234L;
	private final Account logger = new Account(new Id(0, 0, 1002));

	private final Bytes logTopicBytes = Bytes.fromHexString("0xce8688f853ffa65c042b72302433c25d7a230c322caba0901587534b6551091d");

	private final LogTopic logTopic = LogTopic.create(logTopicBytes);

	private final Log log = new Log(logger.getId().asEvmAddress(), Bytes.fromHexString("0x0102"), List.of(
			logTopic,
			logTopic,
			logTopic,
			logTopic
	));

	private final Account recipient = new Account(new Id(0, 0, 1002));

	@Test
	void assertCorrectDataOnSuccessfulTransaction() {

		var firstContract = new Account(new Id(0, 0, 1003));
		var secondContract = new Account(new Id(0, 0, 1004));

		var listOfCreatedContracts = List.of(
				firstContract.getId().asGrpcContract(),
				secondContract.getId().asGrpcContract()
		);

		var log = new Log(logger.getId().asEvmAddress(), Bytes.fromHexString("0x0102"), List.of(
				logTopic,
				logTopic,
				logTopic,
				logTopic
		));
		final var logList = List.of(log);

		final var expect = ContractFunctionResult.newBuilder()
				.setGasUsed(GAS_USAGE)
				.setBloom(ByteString.copyFrom(LogsBloomFilter.builder().insertLogs(logList).build().toArray()));

		expect.setContractCallResult(ByteString.copyFrom(Bytes.EMPTY.toArray()));
		expect.setContractID(EntityIdUtils.contractParsedFromSolidityAddress(recipient.getId().asEvmAddress().toArray()));
		expect.addAllCreatedContractIDs(listOfCreatedContracts);

		var result = TransactionProcessingResult.successful(
				logList,
				GAS_USAGE,
				1234L,
				Bytes.EMPTY,
				recipient.getId().asEvmAddress());
		result.setCreatedContracts(listOfCreatedContracts);

		assertEquals(expect.getGasUsed(), result.getGasUsed());

		assertEquals(Optional.empty(), result.getHaltReason());
		assertEquals(expect.getBloom(), result.toGrpc().getBloom());
		assertEquals(expect.getContractID(), result.toGrpc().getContractID());
		assertEquals(ByteString.EMPTY, result.toGrpc().getContractCallResult());
		assertEquals(listOfCreatedContracts, result.toGrpc().getCreatedContractIDsList());
	}

	@Test
	void assertCorrectDataOnFailedTransaction() {

		var exception = new ExceptionalHaltReason() {
			@Override
			public String name() {
				return "TestExceptionalHaltReason";
			}

			@Override
			public String getDescription() {
				return "Exception Halt Reason Test Description";
			}
		};
		var revertReason = Optional.of(Bytes.fromHexString("0x43"));

		final var expect = ContractFunctionResult.newBuilder()
				.setGasUsed(GAS_USAGE)
				.setBloom(ByteString.copyFrom(new byte[256]));
		expect.setContractCallResult(ByteString.copyFrom(Bytes.EMPTY.toArray()));
		expect.setContractID(EntityIdUtils.contractParsedFromSolidityAddress(recipient.getId().asEvmAddress().toArray()));
		expect.setErrorMessageBytes(ByteString.copyFrom(revertReason.get().toArray()));

		var result = TransactionProcessingResult.failed(GAS_USAGE, GAS_PRICE, revertReason, Optional.of(exception));

		assertEquals(expect.getGasUsed(), result.getGasUsed());
		assertEquals(GAS_PRICE, result.getGasPrice());
		assertEquals(Optional.of(exception), result.getHaltReason());
		assertEquals(revertReason.toString(), result.toGrpc().getErrorMessage());
	}

	@Test
	void assertGasPrise() {
		var result = TransactionProcessingResult.successful(
				List.of(log),
				GAS_USAGE,
				GAS_PRICE,
				Bytes.EMPTY,
				recipient.getId().asEvmAddress());

		assertEquals(GAS_PRICE, result.getGasPrice());
	}

	@Test
	void assertGasUsage() {
		var result = TransactionProcessingResult.successful(
				List.of(log),
				GAS_USAGE,
				GAS_PRICE,
				Bytes.EMPTY,
				recipient.getId().asEvmAddress());

		assertEquals(GAS_USAGE, result.getGasUsed());
	}
	
	@Test
	void assertSuccessfulStatus() {
		var result = TransactionProcessingResult.successful(
				List.of(log),
				GAS_USAGE,
				GAS_PRICE,
				Bytes.EMPTY,
				recipient.getId().asEvmAddress());

		assertTrue(result.isSuccessful());
	}
}