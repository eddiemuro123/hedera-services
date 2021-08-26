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
import com.hedera.services.txns.auth.SystemOpPolicies;
import com.hedera.services.txns.TransitionRunner;
import com.hedera.services.utils.TxnAccessor;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.hedera.services.txns.auth.SystemOpAuthorization.IMPERMISSIBLE;
import static com.hedera.services.txns.auth.SystemOpAuthorization.UNNECESSARY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScreenedTransitionTest {
	@Mock
	private TransitionRunner transitionRunner;
	@Mock
	private SystemOpPolicies opPolicies;
	@Mock
	private TransactionContext txnCtx;
	@Mock
	private NetworkCtxManager networkCtxManager;
	@Mock
	private TxnAccessor accessor;

	private ScreenedTransition subject;

	@BeforeEach
	void setUp() {
		subject = new ScreenedTransition(transitionRunner, opPolicies, txnCtx, networkCtxManager);
	}

	@Test
	void finishesTransitionWithAuthFailure() {
		given(opPolicies.check(accessor)).willReturn(IMPERMISSIBLE);

		// when:
		subject.finishFor(accessor);

		// then:
		verify(txnCtx).setStatus(IMPERMISSIBLE.asStatus());
		verify(transitionRunner, never()).tryTransition(accessor);
	}

	@Test
	void incorporatesAfterFinishingWithSuccess() {
		given(accessor.getFunction()).willReturn(HederaFunctionality.CryptoTransfer);
		given(opPolicies.check(accessor)).willReturn(UNNECESSARY);
		given(transitionRunner.tryTransition(accessor)).willReturn(true);

		// when:
		subject.finishFor(accessor);

		// then:
		verify(transitionRunner).tryTransition(accessor);
		verify(networkCtxManager).finishIncorporating(HederaFunctionality.CryptoTransfer);
	}

	@Test
	void doesntIncorporateAfterFailedTransition() {
		given(opPolicies.check(accessor)).willReturn(UNNECESSARY);

		// when:
		subject.finishFor(accessor);

		// then:
		verify(networkCtxManager, never()).finishIncorporating(any());
	}
}
