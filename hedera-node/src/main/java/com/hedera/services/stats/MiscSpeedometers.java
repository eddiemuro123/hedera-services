package com.hedera.services.stats;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
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

import com.hedera.services.context.properties.NodeLocalProperties;
import com.swirlds.common.Platform;
import com.swirlds.platform.StatsSpeedometer;

public class MiscSpeedometers {
	private final SpeedometerFactory speedometer;

	StatsSpeedometer syncVerifications;
	StatsSpeedometer asyncVerifications;
	StatsSpeedometer accountLookupRetries;
	StatsSpeedometer platformTxnRejections;

	public MiscSpeedometers(SpeedometerFactory speedometer, NodeLocalProperties properties) {
		this.speedometer = speedometer;

		syncVerifications = new StatsSpeedometer(properties.statsSpeedometerHalfLifeSecs());
		asyncVerifications = new StatsSpeedometer(properties.statsSpeedometerHalfLifeSecs());
		accountLookupRetries = new StatsSpeedometer(properties.statsSpeedometerHalfLifeSecs());
		platformTxnRejections = new StatsSpeedometer(properties.statsSpeedometerHalfLifeSecs());
	}

	public void registerWith(Platform platform) {
		throw new AssertionError("Not implemented!");
	}

	public void cycleSyncVerifications() {
		throw new AssertionError("Not implemented!");
	}

	public void cycleAsyncVerifications() {
		throw new AssertionError("Not implemented!");
	}

	public void cycleAccountLookupRetries() {
		throw new AssertionError("Not implemented!");
	}

	public void cyclePlatformTxnRejections() {
		throw new AssertionError("Not implemented!");
	}

	static class Names {
		public static final String SYNC_VERIFICATIONS = "sigVerifySync/sec";
		public static final String ASYNC_VERIFICATIONS = "sigVerifyAsync/sec";
		public static final String ACCOUNT_LOOKUP_RETRIES = "acctLookupRetries/sec";
		public static final String PLATFORM_TXN_REJECTIONS = "platformTxnNotCreated/sec";
	}

	static class Descriptions {
		public static final String SYNC_VERIFICATIONS =
				"number of transactions received per second that must be verified synchronously in handleTransaction";
		public static final String ASYNC_VERIFICATIONS =
				"number of transactions received per second that were verified asynchronously via expandSignatures";
		public static final String ACCOUNT_LOOKUP_RETRIES =
				"number of times per second that an account lookup must be retried";
		public static final String PLATFORM_TXN_REJECTIONS =
				"number of platform transactions not created per second";
	}
}
