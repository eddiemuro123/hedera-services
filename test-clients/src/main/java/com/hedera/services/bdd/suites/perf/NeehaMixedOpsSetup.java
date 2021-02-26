package com.hedera.services.bdd.suites.perf;

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
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freeze;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static java.util.concurrent.TimeUnit.MINUTES;

public class NeehaMixedOpsSetup extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(NeehaMixedOpsSetup.class);

	public static void main(String... args) {
		new NeehaMixedOpsSetup().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(
				new HapiApiSpec[] {
						createNeehaStartState(),
				}
		);
	}

	private HapiApiSpec createNeehaStartState() {
		long ONE_YEAR_IN_SECS = 365 * 24 * 60 * 60;
		int numScheduledTxns = 10;
		return HapiApiSpec.defaultHapiSpec("CreateNeehaStartState")
				.given(
						fileUpdate(APP_PROPERTIES)
								.payingWith(GENESIS)
								.overridingProps(Map.of(
										"ledger.schedule.txExpiryTimeSecs", "" + ONE_YEAR_IN_SECS
								)),
						cryptoCreate("sender")
								.advertisingCreation()
								.balance(ONE_HBAR),
						cryptoCreate("receiver")
								.key(GENESIS)
								.advertisingCreation()
								.balance(0L).receiverSigRequired(true),
						cryptoCreate("tokenTreasury")
								.key(GENESIS)
								.advertisingCreation()
								.balance(ONE_HBAR),
						tokenCreate("wellKnown")
								.advertisingCreation()
								.initialSupply(Long.MAX_VALUE),
						cryptoCreate("tokenReceiver")
								.advertisingCreation(),
						tokenAssociate("tokenReceiver", "wellKnown"),
						createTopic("wellKnownTopic")
								.advertisingCreation()
				).when(
						inParallel(IntStream.range(0, numScheduledTxns).mapToObj(i ->
								scheduleCreate("schedule" + i,
										cryptoTransfer(tinyBarsFromTo( "sender", "receiver", 1))
												.signedBy("sender")
								)
										.signedBy(DEFAULT_PAYER)
										.inheritingScheduledSigs()
						).toArray(HapiSpecOperation[]::new))
				).then(
						freeze().payingWith(GENESIS)
								.startingIn(60).seconds()
								.andLasting(1).minutes()
				);
	}


	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
