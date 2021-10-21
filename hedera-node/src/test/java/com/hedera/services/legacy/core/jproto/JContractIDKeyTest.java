package com.hedera.services.legacy.core.jproto;

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

import com.hedera.services.store.models.Id;
import com.hederahashgraph.api.proto.java.ContractID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JContractIDKeyTest {
	@Test
	void zeroContractIDKeyTest() {
		JContractIDKey key = new JContractIDKey(ContractID.newBuilder().build());
		assertTrue(key.isEmpty());
		assertFalse(key.isValid());
	}

	@Test
	void nonZeroContractIDKeyTest() {
		JContractIDKey key = new JContractIDKey(ContractID.newBuilder().setContractNum(1L).build());
		assertFalse(key.isEmpty());
		assertTrue(key.isValid());
	}

	@Test
	void scheduleOpsAsExpected() {
		var subject = new JContractIDKey(ContractID.newBuilder().setContractNum(1L).build());
		assertFalse(subject.isForScheduledTxn());
		subject.setForScheduledTxn(true);
		assertTrue(subject.isForScheduledTxn());
	}

	@Test
	void getsContractIdKey() {
		final var key = new JContractIDKey(1, 2, 3);
		final var product = key.getContractIDKey();
		assertEquals(key.getShardNum(), product.getShardNum());
		assertEquals(key.getRealmNum(), product.getRealmNum());
		assertEquals(key.getContractNum(), product.getContractNum());
		assertTrue(product.hasContractID());
		assertTrue(!product.toString().isEmpty());
	}

	@Test
	void fromModelIdConversionWorks() {
		final var source = new Id(1, 2, 3);
		final var target = JContractIDKey.fromId(source);
		assertEquals(target.getShardNum(), source.getShard());
		assertEquals(target.getRealmNum(), source.getRealm());
		assertEquals(target.getContractNum(), source.getNum());
	}

	@Test
	void fromJContractIdToContractIdConversionWorks() {
		final var source = new JContractIDKey(1, 2, 3);
		final var target = source.getContractID();
		assertEquals(target.getShardNum(), source.getShardNum());
		assertEquals(target.getRealmNum(), source.getRealmNum());
		assertEquals(target.getContractNum(), source.getContractNum());
	}
}
