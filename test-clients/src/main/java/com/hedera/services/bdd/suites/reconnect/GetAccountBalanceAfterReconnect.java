package com.hedera.services.bdd.suites.reconnect;

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

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;

public class GetAccountBalanceAfterReconnect extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(GetAccountBalanceAfterReconnect.class);

	public static void main(String... args) {
		new GetAccountBalanceAfterReconnect().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(
				getAccountBalanceFromAllNodes()
		);
	}

	private HapiApiSpec getAccountBalanceFromAllNodes() {
		return defaultHapiSpec("GetAccountBalanceFromAllNodes")
				.given().when().then(
						UtilVerbs.sleepFor(4 * 60 * 1000),
						getAccountBalance("0.0.1001").logged().setNode("0.0.3"),
						getAccountBalance("0.0.1002").logged().setNode("0.0.3"),
						getAccountBalance("0.0.1001").logged().setNode("0.0.4"),
						getAccountBalance("0.0.1002").logged().setNode("0.0.4"),
						getAccountBalance("0.0.1001").logged().setNode("0.0.5"),
						getAccountBalance("0.0.1002").logged().setNode("0.0.5"),
						getAccountBalance("0.0.1001").logged().setNode("0.0.6"),
						getAccountBalance("0.0.1002").logged().setNode("0.0.6")
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
