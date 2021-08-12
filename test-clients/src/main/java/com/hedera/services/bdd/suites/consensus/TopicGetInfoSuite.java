package com.hedera.services.bdd.suites.consensus;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTopicId;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;

public class TopicGetInfoSuite extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(TopicGetInfoSuite.class);

	public static void main(String... args) {
		new TopicGetInfoSuite().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(
				new HapiApiSpec[] {
						queryTopic(),
						submitMsg()
				}
		);
	}

	private HapiApiSpec submitMsg() {
		return defaultHapiSpec("topicIdIsValidated")
				.given(
				)
				.when()
				.then(
						submitMessageTo("0.0.1001110")
								.message("Howdy!")
								.hasRetryPrecheckFrom(BUSY)
				);
	}

	private HapiApiSpec queryTopic() {
		// sequenceNumber should be 0 and runningHash should be 48 bytes all 0s.
		return defaultHapiSpec("AllFieldsSetHappyCase")
				.given(
				)
				.when()
				.then(
						getTopicInfo("0.0.1001110")
						.logged()
				);
	}

	private HapiApiSpec postCreateTopicCase() {
		// sequenceNumber should be 0 and runningHash should be 48 bytes all 0s.
		return defaultHapiSpec("AllFieldsSetHappyCase")
				.given(
						newKeyNamed("adminKey"),
						newKeyNamed("submitKey"),
						cryptoCreate("autoRenewAccount"),
						createTopic("testTopic")
								.topicMemo("testmemo")
								.adminKeyName("adminKey")
								.submitKeyName("submitKey")
								.autoRenewAccountId("autoRenewAccount")
				)
				.when()
				.then(
						getTopicInfo("testTopic")
							.hasMemo("testmemo")
							.hasAdminKey("adminKey")
							.hasSubmitKey("submitKey")
							.hasAutoRenewAccount("autoRenewAccount")
							.hasSeqNo(0)
							.hasRunningHash(new byte[48])
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
