package com.hedera.services.txns.consensus;

/*
 * -
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 *
 */

import com.hedera.services.fees.annotations.FunctionKey;
import com.hedera.services.txns.TransitionLogic;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;

import java.util.List;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusCreateTopic;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusDeleteTopic;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusSubmitMessage;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusUpdateTopic;

@Module
public abstract class ConsensusLogicModule {
	@Provides
	@IntoMap
	@FunctionKey(ConsensusCreateTopic)
	public static List<TransitionLogic> provideTopicCreateLogic(TopicCreateTransitionLogic topicCreateLogic ) {
		return List.of(topicCreateLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(ConsensusUpdateTopic)
	public static List<TransitionLogic> provideTopicUpdateLogic(TopicUpdateTransitionLogic topicUpdateLogic) {
		return List.of(topicUpdateLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(ConsensusDeleteTopic)
	public static List<TransitionLogic> provideTopicDeleteLogic(TopicDeleteTransitionLogic topicDeleteLogic) {
		return List.of(topicDeleteLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(ConsensusSubmitMessage)
	public static List<TransitionLogic> provideSubmitMessageLogic(SubmitMessageTransitionLogic submitMessageLogic) {
		return List.of(submitMessageLogic);
	}
}
