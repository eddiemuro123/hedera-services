package com.hedera.services.state.expiry;

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

import com.hedera.services.context.ServicesContext;
import com.hedera.services.stream.RecordStreamObject;
import com.hederahashgraph.api.proto.java.AccountID;

import java.time.Instant;

public class EntityAutoRenewal {
	private static final com.hederahashgraph.api.proto.java.Transaction EMPTY =
			com.hederahashgraph.api.proto.java.Transaction.getDefaultInstance();

	private ServicesContext ctx;

	public EntityAutoRenewal(ServicesContext ctx) {
		this.ctx = ctx;
	}

	public void execute(Instant consensusTime) {
		var props = ctx.globalDynamicProperties();
		if (!props.autoRenewEnabled()) {
			return;
		}
		AccountID feeCollector = props.fundingAccount();
		AccountID.Builder accountBuilder = feeCollector.toBuilder();
		var backingAccounts = ctx.backingAccounts();
		long lastScannedEntity = ctx.lastScannedEntity();
		long numberOfEntitiesRenewedOrDeleted = 0;
		for (long i = 1; i <= props.autoRenewNumberOfEntitiesToScan(); i++) {
			lastScannedEntity++;
			if (lastScannedEntity >= ctx.seqNo().current()) {
				lastScannedEntity = ctx.hederaNums().numReservedSystemEntities() + 1;
			}
			AccountID accountID = accountBuilder
					.setAccountNum(lastScannedEntity)
					.build();
			if (backingAccounts.contains(accountID)) {
				var merkleAccount = backingAccounts.getRef(accountID);
				long expiry = merkleAccount.getExpiry();
				if (expiry <= consensusTime.getEpochSecond()) {
					numberOfEntitiesRenewedOrDeleted++;
					long balance = merkleAccount.getBalance();
					long newExpiry = expiry;
					long fee = 100_000_000L;
					if (0 == balance) {
						backingAccounts.remove(accountID);
					} else {
						long autoRenewSecs = merkleAccount.getAutoRenewSecs();
						newExpiry = expiry + autoRenewSecs;
						merkleAccount.setExpiry(newExpiry);
					}
					Instant actionTime = consensusTime.plusNanos(numberOfEntitiesRenewedOrDeleted);
					var record = (0 == balance)
							? EntityRemovalRecord.generatedFor(accountID, actionTime, accountID)
							: AutoRenewalRecord.generatedFor(accountID, actionTime, accountID, fee, newExpiry, feeCollector);
					var recordStreamObject = new RecordStreamObject(record, EMPTY, actionTime);
					ctx.updateRecordRunningHash(recordStreamObject.getRunningHash());
					ctx.recordStreamManager().addRecordStreamObject(recordStreamObject);
				}
			}
			if (numberOfEntitiesRenewedOrDeleted >= props.autoRenewMaxNumberOfEntitiesToRenewOrDelete()) {
				break;
			}
		}
		backingAccounts.flushMutableRefs();
		ctx.updateLastScannedEntity(lastScannedEntity);
	}
}
