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
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.sigs.Rationalization;
import com.hedera.services.stats.MiscSpeedometers;
import com.hedera.services.utils.TxnAccessor;
import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.hedera.test.extensions.LoggingTarget;
import com.swirlds.common.crypto.TransactionSignature;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.BiPredicate;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith({ MockitoExtension.class, LogCaptureExtension.class })
class SignatureScreenTest {
	@Mock
	private Rationalization rationalization;
	@Mock
	private PayerSigValidity payerSigValidity;
	@Mock
	private TransactionContext txnCtx;
	@Mock
	private MiscSpeedometers speedometers;
	@Mock
	private BiPredicate<JKey, TransactionSignature> validityTest;
	@Mock
	private TxnAccessor accessor;

	@LoggingTarget
	private LogCaptor logCaptor;
	@LoggingSubject
	private SignatureScreen subject;

	@BeforeEach
	void setUp() {
		subject = new SignatureScreen(
				rationalization, payerSigValidity, txnCtx, speedometers, validityTest);
	}

	@Test
	void propagatesRationalizedStatus() {
		given(rationalization.finalStatus()).willReturn(INVALID_ACCOUNT_ID);

		// when:
		final var result = subject.applyTo(accessor);

		// then:
		verify(rationalization).performFor(accessor);
		verifyNoInteractions(speedometers);
		// and:
		Assertions.assertEquals(INVALID_ACCOUNT_ID, result);
	}

	@Test
	void marksPayerSigActiveAndPreparesWhenVerified() {
		givenOkRationalization();
		given(payerSigValidity.test(accessor, validityTest)).willReturn(true);

		// when:
		final var result = subject.applyTo(accessor);

		// then:
		verify(txnCtx).payerSigIsKnownActive();
		// and:
		Assertions.assertEquals(OK, result);
	}

	@Test
	void warnsWhenPayerSigActivationThrows() {
		givenOkRationalization();
		given(payerSigValidity.test(accessor, validityTest)).willThrow(IllegalArgumentException.class);

		// when:
		subject.applyTo(accessor);

		// then:
		assertThat(logCaptor.warnLogs(),
				contains(Matchers.startsWith("Unhandled exception while testing payer sig activation")));
	}

	@Test
	void cyclesSyncWhenUsed() {
		givenOkRationalization(true);

		// when:
		subject.applyTo(accessor);

		// then:
		verify(speedometers).cycleSyncVerifications();
	}

	@Test
	void cyclesAsyncWhenUsed() {
		givenOkRationalization();

		// when:
		subject.applyTo(accessor);

		// then:
		verify(speedometers).cycleAsyncVerifications();
	}

	private void givenOkRationalization() {
		givenOkRationalization(false);
	}

	private void givenOkRationalization(boolean usedSync) {
		given(rationalization.finalStatus()).willReturn(OK);
		if (usedSync) {
			given(rationalization.usedSyncVerification()).willReturn(true);
		}
	}
}
