package com.hedera.services.state.submerkle;

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

import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.ExchangeRateSet;
import com.hederahashgraph.api.proto.java.TimestampSeconds;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.inOrder;
import static org.mockito.BDDMockito.mock;

class ExchangeRatesTest {
	private static final int expCurrentHbarEquiv = 25;
	private static final int expCurrentCentEquiv = 1;
	private static final long expCurrentExpiry = Instant.now().getEpochSecond() + 1_234L;

	private static final int expNextHbarEquiv = 45;
	private static final int expNextCentEquiv = 2;
	private static final long expNextExpiry = Instant.now().getEpochSecond() + 5_678L;

	private static final ExchangeRateSet grpc = ExchangeRateSet.newBuilder()
			.setCurrentRate(ExchangeRate.newBuilder()
					.setHbarEquiv(expCurrentHbarEquiv)
					.setCentEquiv(expCurrentCentEquiv)
					.setExpirationTime(TimestampSeconds.newBuilder().setSeconds(expCurrentExpiry)))
			.setNextRate(ExchangeRate.newBuilder()
					.setHbarEquiv(expNextHbarEquiv)
					.setCentEquiv(expNextCentEquiv)
					.setExpirationTime(TimestampSeconds.newBuilder().setSeconds(expNextExpiry)))
			.build();

	private DataInputStream din;
	private ExchangeRates subject;

	@BeforeEach
	private void setup() {
		din = mock(DataInputStream.class);

		subject = new ExchangeRates(
				expCurrentHbarEquiv, expCurrentCentEquiv, expCurrentExpiry,
				expNextHbarEquiv, expNextCentEquiv, expNextExpiry);
	}

	@Test
	void notAutoInitialized() {
		subject = new ExchangeRates();

		assertFalse(subject.isInitialized());
	}

	@Test
	void copyWorks() {
		final var subjectCopy = subject.copy();

		assertEquals(expCurrentHbarEquiv, subjectCopy.getCurrHbarEquiv());
		assertEquals(expCurrentCentEquiv, subjectCopy.getCurrCentEquiv());
		assertEquals(expCurrentExpiry, subjectCopy.getCurrExpiry());
		assertEquals(expNextHbarEquiv, subjectCopy.getNextHbarEquiv());
		assertEquals(expNextCentEquiv, subjectCopy.getNextCentEquiv());
		assertEquals(expNextExpiry, subjectCopy.getNextExpiry());
		assertEquals(subject, subjectCopy);
		assertTrue(subjectCopy.isInitialized());
	}

	@Test
	void serializesAsExpected() throws IOException {
		final var out = mock(SerializableDataOutputStream.class);
		final var inOrder = inOrder(out);

		subject.serialize(out);

		inOrder.verify(out).writeInt(expCurrentHbarEquiv);
		inOrder.verify(out).writeInt(expCurrentCentEquiv);
		inOrder.verify(out).writeLong(expCurrentExpiry);
		inOrder.verify(out).writeInt(expNextHbarEquiv);
		inOrder.verify(out).writeInt(expNextCentEquiv);
		inOrder.verify(out).writeLong(expNextExpiry);
	}

	@Test
	void deserializesAsExpected() throws IOException {
		final var in = mock(SerializableDataInputStream.class);
		subject = new ExchangeRates();
		given(in.readLong())
				.willReturn(expCurrentExpiry)
				.willReturn(expNextExpiry);
		given(in.readInt())
				.willReturn(expCurrentHbarEquiv)
				.willReturn(expCurrentCentEquiv)
				.willReturn(expNextHbarEquiv)
				.willReturn(expNextCentEquiv);

		subject.deserialize(in, ExchangeRates.MERKLE_VERSION);

		assertEquals(expCurrentHbarEquiv, subject.getCurrHbarEquiv());
		assertEquals(expCurrentCentEquiv, subject.getCurrCentEquiv());
		assertEquals(expCurrentExpiry, subject.getCurrExpiry());
		assertEquals(expNextHbarEquiv, subject.getNextHbarEquiv());
		assertEquals(expNextCentEquiv, subject.getNextCentEquiv());
		assertEquals(expNextExpiry, subject.getNextExpiry());
		assertTrue(subject.isInitialized());
	}

	@Test
	void sanityChecks() {
		assertEquals(expCurrentHbarEquiv, subject.getCurrHbarEquiv());
		assertEquals(expCurrentCentEquiv, subject.getCurrCentEquiv());
		assertEquals(expCurrentExpiry, subject.getCurrExpiry());
		assertEquals(expNextHbarEquiv, subject.getNextHbarEquiv());
		assertEquals(expNextCentEquiv, subject.getNextCentEquiv());
		assertEquals(expNextExpiry, subject.getNextExpiry());
		assertTrue(subject.isInitialized());
	}

	@Test
	void replaces() {
		final var newRates = ExchangeRateSet.newBuilder()
				.setCurrentRate(
						ExchangeRate.newBuilder()
								.setHbarEquiv(expCurrentHbarEquiv)
								.setCentEquiv(expCurrentCentEquiv)
								.setExpirationTime(TimestampSeconds.newBuilder().setSeconds(expCurrentExpiry)))
				.setNextRate(
						ExchangeRate.newBuilder()
								.setHbarEquiv(expNextHbarEquiv)
								.setCentEquiv(expNextCentEquiv)
								.setExpirationTime(TimestampSeconds.newBuilder().setSeconds(expNextExpiry)))
				.build();
		subject = new ExchangeRates();

		subject.replaceWith(newRates);

		assertEquals(expCurrentHbarEquiv, subject.getCurrHbarEquiv());
		assertEquals(expCurrentCentEquiv, subject.getCurrCentEquiv());
		assertEquals(expCurrentExpiry, subject.getCurrExpiry());
		assertEquals(expNextHbarEquiv, subject.getNextHbarEquiv());
		assertEquals(expNextCentEquiv, subject.getNextCentEquiv());
		assertEquals(expNextExpiry, subject.getNextExpiry());
		assertTrue(subject.isInitialized());
	}

	@Test
	void toStringWorks() {
		assertEquals(
				"ExchangeRates{currHbarEquiv=" + expCurrentHbarEquiv +
						", currCentEquiv=" + expCurrentCentEquiv +
						", currExpiry=" + expCurrentExpiry +
						", nextHbarEquiv=" + expNextHbarEquiv +
						", nextCentEquiv=" + expNextCentEquiv +
						", nextExpiry=" + expNextExpiry + "}",
				subject.toString());
	}

	@Test
	void viewWorks() {
		assertEquals(grpc, subject.toGrpc());
	}

	@Test
	void factoryWorks() {
		assertEquals(subject, ExchangeRates.fromGrpc(grpc));
	}

	@Test
	void serializableDetWorks() {
		assertEquals(ExchangeRates.MERKLE_VERSION, subject.getVersion());
		assertEquals(ExchangeRates.RUNTIME_CONSTRUCTABLE_ID, subject.getClassId());
	}
}
