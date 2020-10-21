package com.hedera.services.bdd.suites.crypto;

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
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hedera.services.bdd.suites.SuiteRunner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

import static com.hedera.services.bdd.spec.HapiApiSpec.customHapiSpec;
import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PLATFORM_TRANSACTION_NOT_CREATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

/**
 * When running JRS regression tests using SuiteRunner we need to create unique payer accounts for each test client.
 * This class should be used only for that purpose and not be used in any other testing.
 */
public class CryptoCreateForSuiteRunner extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(CryptoCreateForSuiteRunner.class);
	private String nodes;
	private String defaultNode;

	public CryptoCreateForSuiteRunner(String nodes, String defaultNode) {
		this.nodes = nodes;
		this.defaultNode = defaultNode;
	}

	public static void main(String... args) {
		new CryptoCreateForSuiteRunner("localhost", "0.0.3").runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(
				createAccount()
		);
	}

	private HapiApiSpec createAccount() {
		long initialBalance = 5_000_000_000_000L;

		return customHapiSpec("CreatePayerAccountForEachClient")
				.withProperties(Map.of(
						"nodes", nodes,
						"default.node", "0.0." + defaultNode
				)).given().when().then(
						withOpContext((spec, log) -> {
									// If payer account creation is not success submits a new transaction for the
									// creation
									while (true) {
										var cryptoCreateOp = cryptoCreate("payerAccount")
												.balance(initialBalance)
												.withRecharging()
												.rechargeWindow(3)
												.key(GENESIS)
												.payingWith(GENESIS)
												.hasRetryPrecheckFrom(BUSY, DUPLICATE_TRANSACTION,
														PLATFORM_TRANSACTION_NOT_CREATED).
														via("txn");
										var getRecordOp = getTxnRecord("txn")
												.saveTxnRecordToRegistry("savedTxnRcd").logged();
										CustomSpecAssert.allRunFor(spec, cryptoCreateOp, getRecordOp);
										if (spec.registry().getTransactionRecord(
												"savedTxnRcd").getReceipt().getStatus() == SUCCESS) {
											break;
										}
									}

									var payerAccountInfo = getAccountInfo("payerAccount")
											.saveToRegistry("payerAccountInfo").logged();
									CustomSpecAssert.allRunFor(spec, payerAccountInfo);

									//TODO Should be modified in a different way to avoid setting a static variable of
							// other class
									SuiteRunner.setPayerId(String.format("0.0.%s", spec.registry()
											.getAccountInfo("payerAccountInfo")
											.getAccountID().getAccountNum()));
								}
						));
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
