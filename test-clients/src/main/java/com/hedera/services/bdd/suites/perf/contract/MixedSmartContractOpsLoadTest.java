package com.hedera.services.bdd.suites.perf.contract;

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
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.meta.ContractResources;
import com.hedera.services.bdd.spec.utilops.LoadTest;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.perf.PerfTestLoadSettings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.BALANCE_LOOKUP_ABI;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.randomUtf8Bytes;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;

public class MixedSmartContractOpsLoadTest extends LoadTest {
	private static final Logger log = LogManager.getLogger(MixedSmartContractOpsLoadTest.class);

	public static void main(String... args) {
		parseArgs(args);

		MixedSmartContractOpsLoadTest suite = new MixedSmartContractOpsLoadTest();
		suite.setReportStats(true);
		suite.runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(
				RunMixedSmartContractOps()
		);
	}

	@Override
	public boolean hasInterestingStats() {
		return true;
	}

	protected HapiApiSpec RunMixedSmartContractOps() {
		PerfTestLoadSettings settings = new PerfTestLoadSettings();
		final AtomicInteger createdSoFar = new AtomicInteger(0);
		final byte[] memo = randomUtf8Bytes(memoLength.getAsInt());
		final String byteCode = "contractByteCode";
		final String contractToUpdate = "updatableContract";
		final String CONTRACT_NAME_PREFIX = "testContract";
		final String PAYABLE_FILE = "payableByteCode";
		final String PAYABLE_CONTRACT = "payableContract";
		final String LOOKUP_FILE = "lookUpByteCode";
		final String LOOKUP_CONTRACT = "lookUpContract";
		final String civilianAccount = "civilian";
		final int depositAmount = 1;

		Supplier<HapiSpecOperation[]> mixedOpsBurst = () -> new HapiSpecOperation[] {
				/* create a contract*/
				contractCreate(CONTRACT_NAME_PREFIX + createdSoFar.getAndIncrement())
						.bytecode(byteCode)
						.hasAnyPrecheck()
						.deferStatusResolution(),

				/* update the memo and  do get info on the contract */
				contractUpdate(contractToUpdate)
						.newMemo(new String(memo))
						.hasAnyPrecheck()
						.deferStatusResolution(),

				/* call balance lookup contract and contract to deposit funds*/
				contractCallLocal(LOOKUP_CONTRACT,
						BALANCE_LOOKUP_ABI,
						spec -> new Object[] { spec.registry().getAccountID(civilianAccount).getAccountNum() }
				).payingWith(GENESIS),
				contractCall(PAYABLE_CONTRACT, ContractResources.DEPOSIT_ABI, depositAmount)
						.sending(1)
						.suppressStats(true)
						.deferStatusResolution()
		};
		return defaultHapiSpec("RunMixedSmartContractOps")
				.given(
						withOpContext((spec, ignore) -> settings.setFrom(spec.setup().ciPropertiesMap())),
						logIt(ignore -> settings.toString())
				)
				.when(
						/* create an account */
						cryptoCreate(civilianAccount).balance(ONE_HUNDRED_HBARS),

						/* create a file with some contents and contract with it */
						fileCreate(byteCode).path(ContractResources.VALID_BYTECODE_PATH),
						contractCreate(contractToUpdate).bytecode(byteCode).adminKey(THRESHOLD),

						/* create a contract which does a query to look up balance of the civilan account*/
						fileCreate(LOOKUP_FILE).path(ContractResources.BALANCE_LOOKUP_BYTECODE_PATH),
						contractCreate(LOOKUP_CONTRACT).bytecode(LOOKUP_FILE).adminKey(THRESHOLD),

						/* create a contract that does a transaction to deposit funds*/
						fileCreate(PAYABLE_FILE).path(ContractResources.PAYABLE_CONTRACT_BYTECODE_PATH),
						contractCreate(PAYABLE_CONTRACT).bytecode(PAYABLE_FILE).adminKey(THRESHOLD),

						/* get contract info on all contracts created*/
						getContractInfo(LOOKUP_CONTRACT).hasExpectedInfo().logged(),
						getContractInfo(PAYABLE_CONTRACT).hasExpectedInfo().logged(),
						getContractInfo(contractToUpdate).hasExpectedInfo().logged()
				)
				.then(
						defaultLoadTest(mixedOpsBurst, settings)
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
