package com.hedera.services.fees.calculation.consensus;

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

import com.hedera.services.fees.annotations.FunctionKey;
import com.hedera.services.fees.calculation.QueryResourceUsageEstimator;
import com.hedera.services.fees.calculation.TxnResourceUsageEstimator;
import com.hedera.services.fees.calculation.consensus.queries.GetTopicInfoResourceUsage;
import com.hedera.services.fees.calculation.consensus.txns.CreateTopicResourceUsage;
import com.hedera.services.fees.calculation.consensus.txns.DeleteTopicResourceUsage;
import com.hedera.services.fees.calculation.consensus.txns.UpdateTopicResourceUsage;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoMap;

import java.util.List;
import java.util.Set;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusCreateTopic;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusDeleteTopic;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusUpdateTopic;

@Module
public abstract class ConsensusFeesModule {
	@Provides
	@ElementsIntoSet
	public static Set<QueryResourceUsageEstimator> provideConsensusQueryEstimators(
			GetTopicInfoResourceUsage getTopicInfoResourceUsage
	) {
		return Set.of(getTopicInfoResourceUsage);
	}

	@Provides
	@IntoMap
	@FunctionKey(ConsensusCreateTopic)
	public static List<TxnResourceUsageEstimator> provideTopicCreateEstimator(
			CreateTopicResourceUsage createTopicResourceUsage
	) {
		return List.of(createTopicResourceUsage);
	}

	@Provides
	@IntoMap
	@FunctionKey(ConsensusUpdateTopic)
	public static List<TxnResourceUsageEstimator> provideTopicUpdateEstimator(
			UpdateTopicResourceUsage updateTopicResourceUsage
	) {
		return List.of(updateTopicResourceUsage);
	}

	@Provides
	@IntoMap
	@FunctionKey(ConsensusDeleteTopic)
	public static List<TxnResourceUsageEstimator> provideTopicDeleteEstimator(
			DeleteTopicResourceUsage deleteTopicResourceUsage
	) {
		return List.of(deleteTopicResourceUsage);
	}
}
