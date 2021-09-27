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

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.context.TransactionContext;
import com.hedera.services.state.expiry.EntityAutoRenewal;
import com.hedera.services.state.expiry.ExpiryManager;
import com.hedera.services.stats.ExecutionTimeTracker;
import com.hedera.services.txns.ProcessLogic;
import com.hedera.services.txns.network.UpgradeActions;
import com.hedera.services.txns.span.ExpandHandleSpan;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.swirlds.common.SwirldTransaction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;

@Singleton
public class StandardProcessLogic implements ProcessLogic {
	private static final Logger log = LogManager.getLogger(StandardProcessLogic.class);

	private final ExpiryManager expiries;
	private final UpgradeActions upgradeActions;
	private final InvariantChecks invariantChecks;
	private final ExpandHandleSpan expandHandleSpan;
	private final EntityAutoRenewal autoRenewal;
	private final ServicesTxnManager txnManager;
	private final TransactionContext txnCtx;
	private final ExecutionTimeTracker executionTimeTracker;

	private boolean isFirstHandled = true;

	@Inject
	public StandardProcessLogic(
			ExpiryManager expiries,
			UpgradeActions upgradeActions,
			InvariantChecks invariantChecks,
			ExpandHandleSpan expandHandleSpan,
			EntityAutoRenewal autoRenewal,
			ServicesTxnManager txnManager,
			TransactionContext txnCtx,
			ExecutionTimeTracker executionTimeTracker
	) {
		this.expiries = expiries;
		this.upgradeActions = upgradeActions;
		this.invariantChecks = invariantChecks;
		this.expandHandleSpan = expandHandleSpan;
		this.executionTimeTracker = executionTimeTracker;
		this.autoRenewal = autoRenewal;
		this.txnManager = txnManager;
		this.txnCtx = txnCtx;
	}

	@Override
	public void incorporateConsensusTxn(SwirldTransaction platformTxn, Instant consensusTime, long submittingMember) {
		if (isFirstHandled) {
			upgradeActions.catchUpOnMissedSideEffects();
			isFirstHandled = false;
		}

		try {
			final var accessor = expandHandleSpan.accessorFor(platformTxn);
			Instant effectiveConsensusTime = consensusTime;
			if (accessor.canTriggerTxn()) {
				effectiveConsensusTime = consensusTime.minusNanos(1);
			}

			if (!invariantChecks.holdFor(accessor, effectiveConsensusTime, submittingMember)) {
				return;
			}

			expiries.purge(effectiveConsensusTime.getEpochSecond());
			doProcess(submittingMember, effectiveConsensusTime, accessor);
			autoRenewal.execute(consensusTime);
		} catch (InvalidProtocolBufferException e) {
			log.warn("Consensus platform txn was not gRPC!", e);
		}
	}

	private void doProcess(
			final long submittingMember,
			final Instant consensusTime,
			final PlatformTxnAccessor accessor
	) {
		executionTimeTracker.start();
		txnManager.process(accessor, consensusTime, submittingMember);
		final var triggeredAccessor = txnCtx.triggeredTxn();
		if (triggeredAccessor != null) {
			txnManager.process(triggeredAccessor, consensusTime, submittingMember);
		}
		executionTimeTracker.stop();
	}
}
