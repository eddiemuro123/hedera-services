package com.hedera.services.throttles;

/*-
 * ‌
 * Hedera Services API Utilities
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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GasLimitBucketThrottleTest {

	private static final long BUCKET_CAPACITY = 1_234_568;
	private static final long BEYOND_CAPACITY = 1_234_569;

	GasLimitBucketThrottle subject = new GasLimitBucketThrottle(BUCKET_CAPACITY);

	@Test
	void rejectsUnsupportedTXGasLimit() {
		assertFalse(subject.allow(BEYOND_CAPACITY, 0));
	}

	@Test
	void withExcessElapsedNanosCompletelyEmptiesBucket() {
		assertTrue(subject.allow(BUCKET_CAPACITY, Long.MAX_VALUE));
		assertEquals(0, subject.bucket().capacityFree());
	}

	@Test
	void withZeroElapsedNanosSimplyAdjustsCapacityFree() {
		assertTrue(subject.allow(BUCKET_CAPACITY / 2, 0L));
		assertEquals(BUCKET_CAPACITY / 2, subject.bucket().capacityFree());
	}

	@Test
	void withZeroElapsedNanosRejectsUnavailableCapacity() {
		assertFalse(subject.allow(BEYOND_CAPACITY, 0L));
		assertEquals(BUCKET_CAPACITY, subject.bucket().capacityFree());
	}
}
