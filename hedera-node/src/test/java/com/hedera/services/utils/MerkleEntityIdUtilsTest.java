package com.hedera.services.utils;

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

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.hedera.services.store.models.Id;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TopicID;
import com.swirlds.common.CommonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.hedera.services.utils.EntityIdUtils.accountParsedFromSolidityAddress;
import static com.hedera.services.utils.EntityIdUtils.asLiteralString;
import static com.hedera.services.utils.EntityIdUtils.asSolidityAddress;
import static com.hedera.services.utils.EntityIdUtils.asSolidityAddressHex;
import static com.hedera.services.utils.EntityIdUtils.contractParsedFromSolidityAddress;
import static com.hedera.services.utils.EntityIdUtils.parseAccount;
import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hedera.test.utils.IdUtils.asContract;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MerkleEntityIdUtilsTest {
	@Test
	void correctLiteral() {
		assertEquals("1.2.3", asLiteralString(asAccount("1.2.3")));
		assertEquals("11.22.33", asLiteralString(IdUtils.asFile("11.22.33")));
	}

	@Test
	void serializesExpectedSolidityAddress() {
		final byte[] shardBytes = {
				(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xAB,
		};
		final var shard = Ints.fromByteArray(shardBytes);
		final byte[] realmBytes = {
				(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xCD,
				(byte) 0xFE, (byte) 0x00, (byte) 0x00, (byte) 0xFE,
		};
		final var realm = Longs.fromByteArray(realmBytes);
		final byte[] numBytes = {
				(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xDE,
				(byte) 0xBA, (byte) 0x00, (byte) 0x00, (byte) 0xBA
		};
		final var num = Longs.fromByteArray(numBytes);
		final byte[] expected = {
				(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xAB,
				(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xCD,
				(byte) 0xFE, (byte) 0x00, (byte) 0x00, (byte) 0xFE,
				(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xDE,
				(byte) 0xBA, (byte) 0x00, (byte) 0x00, (byte) 0xBA
		};
		final var equivAccount = asAccount(String.format("%d.%d.%d", shard, realm, num));
		final var equivContract = asContract(String.format("%d.%d.%d", shard, realm, num));

		final var actual = asSolidityAddress(shard, realm, num);
		final var anotherActual = asSolidityAddress(equivContract);
		final var actualHex = asSolidityAddressHex(equivAccount);

		assertArrayEquals(expected, actual);
		assertArrayEquals(expected, anotherActual);
		assertEquals(CommonUtils.hex(expected), actualHex);
		assertEquals(equivAccount, accountParsedFromSolidityAddress(actual));
		assertEquals(equivContract, contractParsedFromSolidityAddress(actual));
	}

	@ParameterizedTest
	@CsvSource({
			"0,Cannot parse '0' due to only 0 dots",
			"0.a.0,Argument 'literal=0.a.0' is not an account",
			"...,Argument 'literal=...' is not an account",
			"1.2.3.4,Argument 'literal=1.2.3.4' is not an account",
			"1.2.three,Argument 'literal=1.2.three' is not an account",
			"1.2.333333333333333333333,Cannot parse '1.2.333333333333333333333' due to overflow"
	})
	void rejectsInvalidAccountLiterals(final String badLiteral, final String desiredMsg) {
		final var e = assertThrows(IllegalArgumentException.class, () -> parseAccount(badLiteral));
		assertEquals(desiredMsg, e.getMessage());
	}

	@ParameterizedTest
	@CsvSource({ "1.0.0", "0.1.0", "0.0.1", "1.2.3" })
	void parsesValidLiteral(final String goodLiteral) {
		assertEquals(asAccount(goodLiteral), parseAccount(goodLiteral));
	}

	@Test
	void prettyPrintsIds() {
		final var id = new Id(1, 2, 3);

		assertEquals("1.2.3", EntityIdUtils.readableId(id));
	}

	@Test
	void prettyPrintsScheduleIds() {
		final var id = ScheduleID.newBuilder().setShardNum(1).setRealmNum(2).setScheduleNum(3).build();

		assertEquals("1.2.3", EntityIdUtils.readableId(id));
	}

	@Test
	void prettyPrintsTokenIds() {
		final var id = TokenID.newBuilder().setShardNum(1).setRealmNum(2).setTokenNum(3).build();

		assertEquals("1.2.3", EntityIdUtils.readableId(id));
	}

	@Test
	void prettyPrintsTopicIds() {
		final var id = TopicID.newBuilder().setShardNum(1).setRealmNum(2).setTopicNum(3).build();

		assertEquals("1.2.3", EntityIdUtils.readableId(id));
	}

	@Test
	void prettyPrintsAccountIds() {
		final var id = AccountID.newBuilder().setShardNum(1).setRealmNum(2).setAccountNum(3).build();

		assertEquals("1.2.3", EntityIdUtils.readableId(id));
	}

	@Test
	void prettyPrintsFileIds() {
		final var id = FileID.newBuilder().setShardNum(1).setRealmNum(2).setFileNum(3).build();

		assertEquals("1.2.3", EntityIdUtils.readableId(id));
	}

	@Test
	void prettyPrintsNftIds() {
		final var tokenID = TokenID.newBuilder().setShardNum(1).setRealmNum(2).setTokenNum(3);
		final var id = NftID.newBuilder().setTokenID(tokenID).setSerialNumber(1).build();

		assertEquals("1.2.3.1", EntityIdUtils.readableId(id));
	}

	@Test
	void givesUpOnNonAccountIds() {
		final String id = "my-account";

		assertEquals(id, EntityIdUtils.readableId(id));
	}

	@Test
	void asContractWorks() {
		final var expected = ContractID.newBuilder().setShardNum(1).setRealmNum(2).setContractNum(3).build();
		final var id = AccountID.newBuilder().setShardNum(1).setRealmNum(2).setAccountNum(3).build();

		final var cid = EntityIdUtils.asContract(id);

		assertEquals(expected, cid);
	}

	@Test
	void asFileWorks() {
		final var expected = FileID.newBuilder().setShardNum(1).setRealmNum(2).setFileNum(3).build();
		final var id = AccountID.newBuilder().setShardNum(1).setRealmNum(2).setAccountNum(3).build();

		final var fid = EntityIdUtils.asFile(id);

		assertEquals(expected, fid);
	}
}
