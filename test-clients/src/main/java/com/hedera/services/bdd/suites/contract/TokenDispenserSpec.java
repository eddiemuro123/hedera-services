package com.hedera.services.bdd.suites.contract;

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

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.infrastructure.meta.ContractResources;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static java.lang.System.arraycopy;

public class TokenDispenserSpec extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(TokenDispenserSpec.class);

	public static void main(String... args) {
		new TokenDispenserSpec().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[] {
						tokenDispenserPoc(),
				}
		);
	}

	/**
	 * Does the following to test the TokenDispenser POC:
	 * <ol>
	 *     <li>Creates a token with a supply of 100 units.</li>
	 *     <li>Instantiates a TokenDispenser contract with the id of the token.</li>
	 *     <li>Updates the token to have the contract as its treasury.</li>
	 *     <li>Associates a token purchaser account to the token.</li>
	 *     <li>Calls the contract's dispense() method, sending 10 tinybars.</li>
	 *     <li>Confirms purchaser account has received 10 tokens.</li>
	 * </ol>
	 */
	private HapiApiSpec tokenDispenserPoc() {
		final var bytecode = "bytecode";
		final var tokenDispenser = "contract";
		final var tokenPurchaser = "civilian";
		final var token = "token";
		final var adminKey = "adminKey";

		final AtomicReference<byte[]> tokenSolidityAddr = new AtomicReference<>();

		final var supplyToDispense = 100;
		final var purchaseTxn = "purchaseTxn";
		final long tinybarsToSpend = 10;

		return defaultHapiSpec("TokenDispenserPoc")
				.given(
						newKeyNamed(adminKey),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(token)
								.adminKey(adminKey)
								.treasury(TOKEN_TREASURY)
								.initialSupply(supplyToDispense)
								.decimals(0)
								.onCreation(tId -> tokenSolidityAddr.set(
										asSolidityAddress((int) tId.getShardNum(), tId.getRealmNum(), tId.getTokenNum()))),
						fileCreate(bytecode)
								.path(ContractResources.TOKEN_DISPENSER_BYTECODE_PATH),
						sourcing(() ->
								contractCreate(
										tokenDispenser,
										ContractResources.CONSTRUCT_DISPENSOR_ABI,
										tokenSolidityAddr.get()
								)
										.bytecode(bytecode)),
						tokenAssociate(tokenDispenser, token),
						tokenUpdate(token).treasury(tokenDispenser)
				).when(
						cryptoCreate(tokenPurchaser).balance(ONE_HUNDRED_HBARS),
						tokenAssociate(tokenPurchaser, token),
						contractCall(tokenDispenser, ContractResources.DISPENSE_ABI)
								.payingWith(tokenPurchaser)
								.via(purchaseTxn)
								.gas(300_000L)
								.sending(tinybarsToSpend)
				).then(
						getTxnRecord(purchaseTxn).logged(),
						getAccountBalance(tokenPurchaser).hasTokenBalance(token, tinybarsToSpend).logged()
				);
	}

	private static byte[] asSolidityAddress(int shard, long realm, long num) {
		byte[] solidityAddress = new byte[20];

		arraycopy(Ints.toByteArray(shard), 0, solidityAddress, 0, 4);
		arraycopy(Longs.toByteArray(realm), 0, solidityAddress, 4, 8);
		arraycopy(Longs.toByteArray(num), 0, solidityAddress, 12, 8);

		return solidityAddress;
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
