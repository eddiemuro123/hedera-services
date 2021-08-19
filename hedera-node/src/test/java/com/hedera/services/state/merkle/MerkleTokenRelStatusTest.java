package com.hedera.services.state.merkle;

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

import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.inOrder;
import static org.mockito.BDDMockito.mock;
import static org.mockito.Mockito.times;

class MerkleTokenRelStatusTest {
	private static final long balance = 666;
	private static final boolean frozen = true;
	private static final boolean kycGranted = true;

	private MerkleTokenRelStatus subject;

	@BeforeEach
	private void setup() {
		subject = new MerkleTokenRelStatus(balance, frozen, kycGranted);
	}

	@Test
	void objectContractMet() {
		final var one = new MerkleTokenRelStatus();
		final var two = new MerkleTokenRelStatus(balance - 1, frozen, kycGranted);
		final var three = new MerkleTokenRelStatus(balance, !frozen, kycGranted);
		final var four = new MerkleTokenRelStatus(balance, frozen, !kycGranted);
		final var five = new MerkleTokenRelStatus(balance, frozen, kycGranted);

		assertNotEquals(null, one);
		assertNotEquals(new Object(), one);
		assertNotEquals(subject, two);
		assertNotEquals(subject, three);
		assertNotEquals(subject, four);
		assertEquals(subject, five);

		assertNotEquals(one.hashCode(), two.hashCode());
		assertEquals(subject.hashCode(), five.hashCode());
	}

	@Test
	void merkleMethodsWork() {
		assertEquals(MerkleTokenRelStatus.MERKLE_VERSION, subject.getVersion());
		assertEquals(MerkleTokenRelStatus.RUNTIME_CONSTRUCTABLE_ID, subject.getClassId());
		assertTrue(subject.isLeaf());
	}

	@Test
	void serializeWorks() throws IOException {
		final var out = mock(SerializableDataOutputStream.class);
		final var inOrder = inOrder(out);

		subject.serialize(out);

		inOrder.verify(out).writeLong(balance);
		inOrder.verify(out, times(2)).writeBoolean(true);
	}

	@Test
	void deserializeWorks() throws IOException {
		final var in = mock(SerializableDataInputStream.class);
		final var defaultSubject = new MerkleTokenRelStatus();
		given(in.readLong()).willReturn(balance);
		given(in.readBoolean()).willReturn(frozen).willReturn(kycGranted);

		defaultSubject.deserialize(in, MerkleTokenRelStatus.MERKLE_VERSION);

		assertEquals(subject, defaultSubject);
	}

	@Test
	void toStringWorks() {
		assertEquals(
				"MerkleTokenRelStatus{balance=" + balance
						+ ", isFrozen=" + frozen
						+ ", hasKycGranted=" + kycGranted
						+ "}",
				subject.toString());
	}

	@Test
	void copyWorks() {
		final var subjectCopy = subject.copy();

		assertNotSame(subject, subjectCopy);
		assertEquals(subject, subjectCopy);
		assertTrue(subject.isImmutable());
	}

	@Test
	void deleteIsNoop() {
		assertDoesNotThrow(subject::release);
	}

	@Test
	void throwsOnNegativeBalance() {
		assertThrows(IllegalArgumentException.class, () -> subject.setBalance(-1));
	}
}
