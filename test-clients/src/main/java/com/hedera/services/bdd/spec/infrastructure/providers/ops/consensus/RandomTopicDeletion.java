package com.hedera.services.bdd.spec.infrastructure.providers.ops.consensus;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.LookupUtils;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.transactions.consensus.HapiTopicDelete;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoDelete;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TopicID;

import java.util.List;
import java.util.Optional;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOPIC_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOPIC_EXPIRED_VALUE;
import static java.util.Collections.EMPTY_LIST;

public class RandomTopicDeletion implements OpProvider {
	private final RegistrySourcedNameProvider<TopicID> topics;
	private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(
			TOPIC_EXPIRED,
			INVALID_TOPIC_ID
	);

	public RandomTopicDeletion(RegistrySourcedNameProvider<TopicID> topics) {
		this.topics = topics;
	}

	@Override
	public List<HapiSpecOperation> suggestedInitializers() {
		return EMPTY_LIST;
	}

	@Override
	public Optional<HapiSpecOperation> get() {
		final var topic = topics.getQualifying();
		if (topic.isEmpty()) {
			return Optional.empty();
		}
		HapiTopicDelete op = deleteTopic(topic.get())
				.hasKnownStatusFrom(permissibleOutcomes);
		return Optional.of(op);
	}
}
