package com.hedera.services.state.logic;

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

import com.hedera.services.context.TransactionContext;
import com.hedera.services.keys.InHandleActivationHelper;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.swirlds.common.crypto.TransactionSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KeyActivationScreenTest {
	@Mock
	private TransactionContext txnCtx;
	@Mock
	private InHandleActivationHelper activationHelper;
	@Mock
	private Predicate<ResponseCodeEnum> terminalSigStatusTest;
	@Mock
	private BiPredicate<JKey, TransactionSignature> validityTest;

	private KeyActivationScreen subject;

	@BeforeEach
	void setUp() {
		subject = new KeyActivationScreen(txnCtx, activationHelper, terminalSigStatusTest, validityTest);
	}

	@Test
	void terminatesWithFailedSigStatus() {
		given(terminalSigStatusTest.test(INVALID_ACCOUNT_ID)).willReturn(true);

		// when:
		final var result = subject.reqKeysAreActiveGiven(INVALID_ACCOUNT_ID);

		// then:
		assertFalse(result);
		// and:
		verify(txnCtx).setStatus(INVALID_ACCOUNT_ID);
	}

	@Test
	void terminatesWhenOtherPartySigsNotActive() {
		// when:
		final var result = subject.reqKeysAreActiveGiven(OK);

		// then:
		assertFalse(result);
		// and:
		verify(activationHelper).areOtherPartiesActive(validityTest);
		verify(txnCtx).setStatus(INVALID_SIGNATURE);
	}

	@Test
	void oksValidSigs() {
		given(activationHelper.areOtherPartiesActive(validityTest)).willReturn(true);

		// when:
		final var result = subject.reqKeysAreActiveGiven(OK);

		// then:
		assertTrue(result);
	}
}
