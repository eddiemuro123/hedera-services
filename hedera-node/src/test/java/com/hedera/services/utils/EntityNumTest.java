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

import com.hedera.services.store.models.Id;
import com.hedera.test.utils.IdUtils;
import org.junit.jupiter.api.Test;

import static com.hedera.services.utils.EntityNum.MISSING_NUM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityNumTest {
	@Test
	void overridesJavaLangImpl() {
		final var v = 1_234_567;

		final var subject = new EntityNum(v);

		assertNotEquals(v, subject.hashCode());
	}

	@Test
	void equalsWorks() {
		final var a = new EntityNum(1);
		final var b = new EntityNum(2);
		final var c = a;

		assertNotEquals(a, b);
		assertNotEquals(null, a);
		assertNotEquals(new Object(), a);
		assertEquals(a, c);
	}

	@Test
	void returnsMissingNumForUnusableNum() {
		assertEquals(MISSING_NUM, EntityNum.fromLong(Long.MAX_VALUE));
	}

	@Test
	void factoriesWorkForValidShardRealm() {
		final var expected = EntityNum.fromInt(123);

		assertEquals(expected, EntityNum.fromLong(123L));
		assertEquals(expected, EntityNum.fromAccountId(IdUtils.asAccount("0.0.123")));
		assertEquals(expected, EntityNum.fromTokenId(IdUtils.asToken("0.0.123")));
		assertEquals(expected, EntityNum.fromScheduleId(IdUtils.asSchedule("0.0.123")));
		assertEquals(expected, EntityNum.fromTopicId(IdUtils.asTopic("0.0.123")));
		assertEquals(expected, EntityNum.fromContractId(IdUtils.asContract("0.0.123")));
		assertEquals(expected, EntityNum.fromModel(new Id(0, 0, 123)));
	}

	@Test
	void factoriesWorkForInvalidShard() {
		final var expectedVal = MISSING_NUM;

		assertEquals(expectedVal, EntityNum.fromAccountId(IdUtils.asAccount("1.0.123")));
		assertEquals(expectedVal, EntityNum.fromTokenId(IdUtils.asToken("1.0.123")));
		assertEquals(expectedVal, EntityNum.fromScheduleId(IdUtils.asSchedule("1.0.123")));
		assertEquals(expectedVal, EntityNum.fromTopicId(IdUtils.asTopic("1.0.123")));
		assertEquals(expectedVal, EntityNum.fromContractId(IdUtils.asContract("1.0.123")));
		assertEquals(expectedVal, EntityNum.fromModel(new Id(1, 0, 123)));
	}

	@Test
	void factoriesWorkForInvalidRealm() {
		final var expectedVal = MISSING_NUM;

		assertEquals(expectedVal, EntityNum.fromAccountId(IdUtils.asAccount("0.1.123")));
		assertEquals(expectedVal, EntityNum.fromTokenId(IdUtils.asToken("0.1.123")));
		assertEquals(expectedVal, EntityNum.fromScheduleId(IdUtils.asSchedule("0.1.123")));
		assertEquals(expectedVal, EntityNum.fromTopicId(IdUtils.asTopic("0.1.123")));
		assertEquals(expectedVal, EntityNum.fromContractId(IdUtils.asContract("0.1.123")));
		assertEquals(expectedVal, EntityNum.fromModel(new Id(0, 1, 123)));
	}

	@Test
	void viewsWork() {
		final var subject = EntityNum.fromInt(123);

		assertEquals(123, subject.toGrpcAccountId().getAccountNum());
		assertEquals(123, subject.toGrpcTokenId().getTokenNum());
		assertEquals(123, subject.toGrpcScheduleId().getScheduleNum());
		assertTrue(subject.toIdString().endsWith(".123"));
	}

	@Test
	void viewsWorkEvenForNegativeNumCodes() {
		final long realNum = (long) Integer.MAX_VALUE + 10;

		final var subject = EntityNum.fromLong(realNum);

		assertEquals(realNum, subject.toGrpcAccountId().getAccountNum());
		assertEquals(realNum, subject.toGrpcTokenId().getTokenNum());
		assertEquals(realNum, subject.toGrpcScheduleId().getScheduleNum());
		assertTrue(subject.toIdString().endsWith("." + realNum));
	}

	@Test
	void canGetLongValue() {
		final long realNum = (long) Integer.MAX_VALUE + 10;

		final var subject = EntityNum.fromLong(realNum);

		assertEquals(realNum, subject.longValue());
	}
}
