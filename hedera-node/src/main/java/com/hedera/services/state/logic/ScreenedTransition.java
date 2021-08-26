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

import javax.inject.Inject;
import javax.inject.Singleton;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

@Singleton
public class ScreenedTransition {
	private final TransitionRunner transitionRunner;
	private final SystemOpPolicies opPolicies;
	private final TransactionContext txnCtx;
	private final NetworkCtxManager networkCtxManager;

	@Inject
	public ScreenedTransition(
			TransitionRunner transitionRunner,
			SystemOpPolicies opPolicies,
			TransactionContext txnCtx,
			NetworkCtxManager networkCtxManager
	) {
		this.transitionRunner = transitionRunner;
		this.opPolicies = opPolicies;
		this.txnCtx = txnCtx;
		this.networkCtxManager = networkCtxManager;
	}

	void finishFor(TxnAccessor accessor) {
		final var sysAuthStatus = opPolicies.check(accessor).asStatus();
		if (sysAuthStatus != OK) {
			txnCtx.setStatus(sysAuthStatus);
			return;
		}
		if (transitionRunner.tryTransition(accessor)) {
			networkCtxManager.finishIncorporating(accessor.getFunction());
		}
	}
}
