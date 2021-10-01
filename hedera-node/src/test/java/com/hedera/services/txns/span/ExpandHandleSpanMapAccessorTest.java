package com.hedera.services.txns.span;

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

import com.hedera.services.usage.crypto.CryptoCreateMeta;
import com.hedera.services.usage.crypto.CryptoUpdateMeta;
import com.hedera.services.utils.TxnAccessor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static com.hedera.services.usage.token.TokenOpsUsageUtils.TOKEN_OPS_USAGE_UTILS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ExpandHandleSpanMapAccessorTest {
	private Map<String, Object>	span = new HashMap<>();

	@Mock
	private TxnAccessor accessor;

	private ExpandHandleSpanMapAccessor subject;

	@BeforeEach
	void setUp() {
		subject = new ExpandHandleSpanMapAccessor();

		given(accessor.getSpanMap()).willReturn(span);
	}

	@Test
	void testsForImpliedXfersAsExpected() {
		Assertions.assertDoesNotThrow(() -> subject.getImpliedTransfers(accessor));
	}

	@Test
	void testsForTokenCreateMetaAsExpected() {
		Assertions.assertDoesNotThrow(() -> subject.getTokenCreateMeta(accessor));
	}

	@Test
	void testsForTokenBurnMetaAsExpected() {
		Assertions.assertDoesNotThrow(() -> subject.getTokenBurnMeta(accessor));
	}

	@Test
	void testsForTokenWipeMetaAsExpected() {
		Assertions.assertDoesNotThrow(() -> subject.getTokenWipeMeta(accessor));
	}

	@Test
	void testsForTokenFreezeMetaAsExpected() {
		final var tokenFreezeMeta = TOKEN_OPS_USAGE_UTILS.tokenFreezeUsageFrom();

		subject.setTokenFreezeMeta(accessor, tokenFreezeMeta);

		assertEquals(48,  subject.getTokenFreezeMeta(accessor).getBpt());
	}

	@Test
	void testsForTokenUnfreezeMetaAsExpected() {
		final var tokenUnfreezeMeta = TOKEN_OPS_USAGE_UTILS.tokenUnfreezeUsageFrom();

		subject.setTokenUnfreezeMeta(accessor, tokenUnfreezeMeta);

		assertEquals(48,  subject.getTokenUnfreezeMeta(accessor).getBpt());
	}

	@Test
	void testsForTokenPauseMetaAsExpected() {
		final var tokenPauseMeta = TOKEN_OPS_USAGE_UTILS.tokenPauseUsageFrom();

		subject.setTokenPauseMeta(accessor, tokenPauseMeta);

		assertEquals(24,  subject.getTokenPauseMeta(accessor).getBpt());
	}

	@Test
	void testsForTokenUnpauseMetaAsExpected() {
		final var tokenUnpauseMeta = TOKEN_OPS_USAGE_UTILS.tokenUnpauseUsageFrom();

		subject.setTokenUnpauseMeta(accessor, tokenUnpauseMeta);

		assertEquals(24,  subject.getTokenUnpauseMeta(accessor).getBpt());
	}

	@Test
	void testsForCryptoCreateMetaAsExpected() {
		var opMeta = new CryptoCreateMeta.Builder()
				.baseSize(1_234)
				.lifeTime(1_234_567L)
				.maxAutomaticAssociations(12)
				.build();

		subject.setCryptoCreateMeta(accessor, opMeta);

		assertEquals(1_234, subject.getCryptoCreateMeta(accessor).getBaseSize());
	}

	@Test
	void testsForCryptoUpdateMetaAsExpected() {
		final var opMeta = new CryptoUpdateMeta.Builder()
				.keyBytesUsed(123)
				.msgBytesUsed(1_234)
				.memoSize(100)
				.effectiveNow(1_234_000L)
				.expiry(1_234_567L)
				.hasProxy(false)
				.maxAutomaticAssociations(3)
				.hasMaxAutomaticAssociations(true)
				.build();

		subject.setCryptoUpdate(accessor, opMeta);

		assertEquals(3, subject.getCryptoUpdateMeta(accessor).getMaxAutomaticAssociations());
	}
}
