package com.hedera.services.usage.schedule;

/*
 * ‌
 * Hedera Services API Fees
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.google.protobuf.ByteString;
import com.hedera.services.usage.QueryUsage;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.fee.FeeBuilder;

import java.util.List;
import java.util.Optional;

import static com.hedera.services.usage.schedule.entities.ScheduleEntitySizes.SCHEDULE_ENTITY_SIZES;
import static com.hederahashgraph.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;

public class ScheduleGetInfoUsage extends QueryUsage {

	private ScheduleGetInfoUsage(Query query) {
		super(query.getScheduleGetInfo().getHeader().getResponseType());
		updateTb(BASIC_ENTITY_ID_SIZE);
		updateRb(SCHEDULE_ENTITY_SIZES.fixedBytesInScheduleRepr());
	}

	public static ScheduleGetInfoUsage newEstimate(Query query) {
		return new ScheduleGetInfoUsage(query);
	}

	public ScheduleGetInfoUsage givenCurrentAdminKey(Optional<Key> adminKey) {
		adminKey.map(FeeBuilder::getAccountKeyStorageSize).ifPresent(this::updateRb);
		return this;
	}

	public ScheduleGetInfoUsage givenTransaction(byte[] transactionBody) {
		this.updateRb(transactionBody.length);
		return this;
	}

	public ScheduleGetInfoUsage givenMemo(ByteString memo) {
		this.updateRb(memo.size());
		return this;
	}

	public ScheduleGetInfoUsage givenSignatories(Optional<KeyList> signatories) {
		signatories.map(kl -> kl.toByteArray().length).ifPresent(this::updateRb);
		return this;
	}

}
