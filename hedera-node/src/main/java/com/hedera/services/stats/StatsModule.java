package com.hedera.services.stats;

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
import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.utils.MiscUtils;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

@Module
public final class StatsModule {
	@Provides
	@Singleton
	public static MiscRunningAvgs provideMiscRunningAvgs(final NodeLocalProperties nodeLocalProperties) {
		return new MiscRunningAvgs(new RunningAvgFactory() {
		}, nodeLocalProperties.statsRunningAvgHalfLifeSecs());
	}

	@Provides
	@Singleton
	public static MiscSpeedometers provideMiscSpeedometers(final NodeLocalProperties nodeLocalProperties) {
		return new MiscSpeedometers(new SpeedometerFactory() {
		}, nodeLocalProperties.statsSpeedometerHalfLifeSecs());
	}

	@Provides
	@Singleton
	public static HapiOpSpeedometers provideHapiOpSpeedometers(
			final HapiOpCounters counters,
			final NodeLocalProperties nodeLocalProperties
	) {
		return new HapiOpSpeedometers(
				counters,
				new SpeedometerFactory() {
				},
				nodeLocalProperties,
				MiscUtils::baseStatNameOf);
	}

	@Provides
	@Singleton
	public static HapiOpCounters provideHapiOpCounters(
			final MiscRunningAvgs runningAvgs,
			final TransactionContext txnCtx) {
		return new HapiOpCounters(new CounterFactory() {
		}, runningAvgs, txnCtx, MiscUtils::baseStatNameOf);
	}

	private StatsModule() {
		throw new UnsupportedOperationException("Dagger2 module");
	}
}
