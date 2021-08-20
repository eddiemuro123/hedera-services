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
import com.hedera.services.txns.ProcessLogic;
import com.hedera.services.txns.span.ExpandHandleSpan;
import com.swirlds.common.SwirldTransaction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;

public class StandardProcessLogic implements ProcessLogic {
	private static final Logger log = LogManager.getLogger(StandardProcessLogic.class);

	private final ExpiryManager expiries;
	private final InvariantChecks invariantChecks;
	private final ExpandHandleSpan expandHandleSpan;
	private final EntityAutoRenewal autoRenewal;
	private final ServicesTxnManager txnManager;
	private final TransactionContext txnCtx;

	public StandardProcessLogic(
			ExpiryManager expiries,
			InvariantChecks invariantChecks,
			ExpandHandleSpan expandHandleSpan,
			EntityAutoRenewal autoRenewal,
			ServicesTxnManager txnManager,
			TransactionContext txnCtx
	) {
		this.expiries = expiries;
		this.invariantChecks = invariantChecks;
		this.expandHandleSpan = expandHandleSpan;
		this.autoRenewal = autoRenewal;
		this.txnManager = txnManager;
		this.txnCtx = txnCtx;
	}

	@Override
	public void incorporateConsensusTxn(SwirldTransaction platformTxn, Instant consensusTime, long submittingMember) {
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
			txnManager.process(accessor, effectiveConsensusTime, submittingMember);
			final var triggeredAccessor = txnCtx.triggeredTxn();
			if (triggeredAccessor != null) {
				txnManager.process(triggeredAccessor, consensusTime, submittingMember);
			}

			autoRenewal.execute(consensusTime);
		} catch (InvalidProtocolBufferException e) {
			log.warn("Consensus platform txn was not gRPC!", e);
		}
	}
}
