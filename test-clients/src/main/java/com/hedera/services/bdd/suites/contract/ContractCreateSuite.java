package com.hedera.services.bdd.suites.contract;

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
import com.hedera.services.bdd.spec.HapiSpecSetup;

import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;

import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import static com.hedera.services.bdd.spec.HapiApiSpec.*;

import java.util.Arrays;
import java.util.List;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;
import static com.hedera.services.bdd.spec.keys.ControlForKey.*;

public class ContractCreateSuite extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(ContractCreateSuite.class);

	final String PATH_TO_INVALID_BYTECODE = "src/main/resource/testfiles/CorruptOne.bin";
	final String PATH_TO_VALID_BYTECODE = HapiSpecSetup.getDefaultInstance().defaultContractPath();

	public static void main(String... args) {
		new ContractCreateSuite().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return allOf(
//				positiveTests(),
				negativeTests()
		);
	}

	private List<HapiApiSpec> positiveTests() {
		return Arrays.asList(
			createsVanillaContract()
		);
	}

	private List<HapiApiSpec> negativeTests() {
		return Arrays.asList(
//				rejectsInsufficientFee(),
//				rejectsInvalidBytecode(),
//				revertsNonzeroBalance(),
				createFailsIfMissingSigs()
		);
	}

	private HapiApiSpec createsVanillaContract() {
		return defaultHapiSpec("CreatesVanillaContract")
				.given(
						TxnVerbs.fileCreate("contractFile")
								.path(PATH_TO_VALID_BYTECODE)
				).when().then(
						TxnVerbs.contractCreate("testContract")
								.bytecode("contractFile")
								.hasKnownStatus(SUCCESS)
				);
	}

	/* C.f. https://github.com/swirlds/services-hedera/issues/1728 */
	private HapiApiSpec createFailsIfMissingSigs() {
		KeyShape shape = listOf(SIMPLE, threshOf(2, 3), threshOf(1, 3));
		SigControl validSig = shape.signedWith(sigs(ON, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));
		SigControl invalidSig = shape.signedWith(sigs(OFF, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));

		return defaultHapiSpec("CreateFailsIfMissingSigs")
				.given(
						TxnVerbs.fileCreate("contractFile")
								.path(PATH_TO_VALID_BYTECODE)
				).when().then(
						TxnVerbs.contractCreate("testContract")
								.adminKeyShape(shape)
								.bytecode("contractFile")
								.sigControl(forKey("testContract", invalidSig))
								.hasKnownStatus(INVALID_SIGNATURE),
						TxnVerbs.contractCreate("testContract")
								.adminKeyShape(shape)
								.bytecode("contractFile")
								.sigControl(forKey("testContract", validSig))
				);
	}

	private HapiApiSpec rejectsInsufficientFee() {
		return defaultHapiSpec("RejectsInsufficientFee")
				.given(
						TxnVerbs.fileCreate("contractFile")
								.path(PATH_TO_VALID_BYTECODE)
				).when().then(
						TxnVerbs.contractCreate("testContract")
								.bytecode("contractFile")
								.fee(1L)
								.hasPrecheck(INSUFFICIENT_TX_FEE)
				);
	}

	private HapiApiSpec rejectsInvalidBytecode() {
		return defaultHapiSpec("RejectsInvalidBytecode")
				.given(
						TxnVerbs.fileCreate("contractFile")
								.path(PATH_TO_INVALID_BYTECODE)
				).when().then(
						TxnVerbs.contractCreate("testContract")
								.bytecode("contractFile")
								.hasKnownStatus(ERROR_DECODING_BYTESTRING)
				);
	}

	private HapiApiSpec revertsNonzeroBalance() {
		return defaultHapiSpec("RevertsNonzeroBalance")
				.given(
						TxnVerbs.fileCreate("contractFile")
								.path(PATH_TO_VALID_BYTECODE)
				).when().then(
						TxnVerbs.contractCreate("testContract")
								.balance(1L)
								.bytecode("contractFile")
								.hasKnownStatus(CONTRACT_REVERT_EXECUTED)
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
