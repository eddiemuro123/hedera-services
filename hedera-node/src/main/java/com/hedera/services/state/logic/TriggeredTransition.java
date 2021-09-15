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
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.fees.FeeCalculator;
import com.hedera.services.fees.charging.FeeChargingPolicy;

import javax.inject.Inject;
import javax.inject.Singleton;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

@Singleton
public class TriggeredTransition implements Runnable {
	private final StateView currentView;
	private final FeeCalculator fees;
	private final FeeChargingPolicy chargingPolicy;
	private final NetworkCtxManager networkCtxManager;
	private final ScreenedTransition screenedTransition;
	private final TransactionContext txnCtx;

	@Inject
	public TriggeredTransition(
			StateView currentView,
			FeeCalculator fees,
			FeeChargingPolicy chargingPolicy,
			TransactionContext txnCtx,
			NetworkCtxManager networkCtxManager,
			ScreenedTransition screenedTransition
	) {
		this.currentView = currentView;
		this.fees = fees;
		this.chargingPolicy = chargingPolicy;
		this.txnCtx = txnCtx;
		this.networkCtxManager = networkCtxManager;
		this.screenedTransition = screenedTransition;
	}

	@Override
	public void run() {
		final var accessor = txnCtx.accessor();
		final var now = txnCtx.consensusTime();

		networkCtxManager.advanceConsensusClockTo(now);
		networkCtxManager.prepareForIncorporating(accessor);

		final var fee = fees.computeFee(accessor, txnCtx.activePayerKey(), currentView, now);
		final var chargingOutcome = chargingPolicy.applyForTriggered(fee);
		if (chargingOutcome != OK) {
			txnCtx.setStatus(chargingOutcome);
			return;
		}

		screenedTransition.finishFor(accessor);
	}
}
