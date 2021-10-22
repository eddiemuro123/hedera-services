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

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.infrastructure.meta.ContractResources;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ContractGetInfoResponse;
import com.hederahashgraph.api.proto.java.CryptoGetInfoResponse;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.fee.FeeBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ethereum.core.CallTransaction;
import org.junit.jupiter.api.Assertions;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isContractWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.APPROVE_ABI;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.BALANCE_OF_ABI;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.DECIMALS_ABI;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.OC_TOKEN_BYTECODE_PATH;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.SYMBOL_ABI;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.TOKEN_ERC20_CONSTRUCTOR_ABI;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.TRANSFER_ABI;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.TRANSFER_FROM_ABI;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.contractListWithPropertiesInheritedFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OBTAINER_SAME_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

public class ContractCallSuite extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(ContractCallSuite.class);
	private static final long depositAmount = 1000;

	public static void main(String... args) {
		new ContractCallSuite().runSuiteAsync();
	}

	@Override
	public boolean canRunAsync() {
		return true;
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return allOf(
				positiveSpecs(),
				negativeSpecs()
		);
	}

	List<HapiApiSpec> negativeSpecs() {
		return Arrays.asList(
				insufficientGas(),
				invalidContract(),
				smartContractFailFirst(),
				contractTransferToSigReqAccountWithoutKeyFails()
		);
	}

	List<HapiApiSpec> positiveSpecs() {
		return Arrays.asList(
				resultSizeAffectsFees(),
				payableSuccess(),
				depositSuccess(),
				depositDeleteSuccess(),
				multipleDepositSuccess(),
				payTestSelfDestructCall(),
				multipleSelfDestructsAreSafe(),
				smartContractInlineAssemblyCheck(),
				ocToken(),
				contractTransferToSigReqAccountWithKeySucceeds()
		);
	}

	HapiApiSpec ocToken() {
		return defaultHapiSpec("ocToken")
				.given(
						cryptoCreate("tokenIssuer").balance(1_000_000_000_000L),
						cryptoCreate("Alice").balance(10_000_000_000L).payingWith("tokenIssuer"),
						cryptoCreate("Bob").balance(10_000_000_000L).payingWith("tokenIssuer"),
						cryptoCreate("Carol").balance(10_000_000_000L).payingWith("tokenIssuer"),
						cryptoCreate("Dave").balance(10_000_000_000L).payingWith("tokenIssuer"),

						getAccountInfo("tokenIssuer").savingSnapshot("tokenIssuerAcctInfo"),
						getAccountInfo("Alice").savingSnapshot("AliceAcctInfo"),
						getAccountInfo("Bob").savingSnapshot("BobAcctInfo"),
						getAccountInfo("Carol").savingSnapshot("CarolAcctInfo"),
						getAccountInfo("Dave").savingSnapshot("DaveAcctInfo"),

						fileCreate("bytecode")
								.path(OC_TOKEN_BYTECODE_PATH),

						contractCreate("tokenContract", TOKEN_ERC20_CONSTRUCTOR_ABI,
								1_000_000L, "OpenCrowd Token", "OCT")
								.gas(250_000L)
								.payingWith("tokenIssuer")
								.bytecode("bytecode")
								.via("tokenCreateTxn").logged()
				).when(
						assertionsHold((spec, ctxLog) -> {
							String issuerEthAddress = spec.registry().getAccountInfo("tokenIssuerAcctInfo")
									.getContractAccountID();
							String aliceEthAddress = spec.registry().getAccountInfo("AliceAcctInfo")
									.getContractAccountID();
							String bobEthAddress = spec.registry().getAccountInfo("BobAcctInfo")
									.getContractAccountID();
							String carolEthAddress = spec.registry().getAccountInfo("CarolAcctInfo")
									.getContractAccountID();
							String daveEthAddress = spec.registry().getAccountInfo("DaveAcctInfo")
									.getContractAccountID();

							var subop1 = getContractInfo("tokenContract")
									.nodePayment(10L)
									.saveToRegistry("tokenContract");

							var subop3 = contractCallLocal("tokenContract", DECIMALS_ABI)
									.saveResultTo("decimals")
									.payingWith("tokenIssuer");

							// Note: This contract call will cause a INSUFFICIENT_TX_FEE error, not sure why.
							var subop4 = contractCallLocal("tokenContract", SYMBOL_ABI)
									.saveResultTo("token_symbol")
									.payingWith("tokenIssuer")
									.hasAnswerOnlyPrecheckFrom(OK, INSUFFICIENT_TX_FEE);

							var subop5 = contractCallLocal("tokenContract", BALANCE_OF_ABI, issuerEthAddress)
									.gas(250_000L)
									.saveResultTo("issuerTokenBalance");

							CustomSpecAssert.allRunFor(spec, subop1, subop3, subop4, subop5);

							CallTransaction.Function funcSymbol =
									CallTransaction.Function.fromJsonInterface(SYMBOL_ABI);

							String symbol = (String) getValueFromRegistry(spec, "token_symbol", funcSymbol);

							ctxLog.info("symbol: [{}]", symbol);
							Assertions.assertEquals(
									"", symbol,
									"TokenIssuer's symbol should be fixed value"); // should be "OCT" as expected

							CallTransaction.Function funcDecimals = CallTransaction.Function.fromJsonInterface(
									DECIMALS_ABI);

							//long decimals = getLongValueFromRegistry(spec, "decimals", function);
							BigInteger val = getValueFromRegistry(spec, "decimals", funcDecimals);
							long decimals = val.longValue();

							ctxLog.info("decimals {}", decimals);
							Assertions.assertEquals(
									3, decimals,
									"TokenIssuer's decimals should be fixed value");

							long tokenMultiplier = (long) Math.pow(10, decimals);

							CallTransaction.Function function = CallTransaction.Function.fromJsonInterface(
									BALANCE_OF_ABI);

							long issuerBalance = ((BigInteger) getValueFromRegistry(spec, "issuerTokenBalance",
									function)).longValue();

							ctxLog.info("initial balance of Issuer {}", issuerBalance / tokenMultiplier);
							Assertions.assertEquals(
									1_000_000, issuerBalance / tokenMultiplier,
									"TokenIssuer's initial token balance should be 1_000_000");

							//  Do token transfers
							var subop6 = contractCall("tokenContract", TRANSFER_ABI,
									aliceEthAddress, 1000 * tokenMultiplier)
									.gas(250_000L)
									.payingWith("tokenIssuer");

							var subop7 = contractCall("tokenContract", TRANSFER_ABI,
									bobEthAddress, 2000 * tokenMultiplier)
									.gas(250_000L)
									.payingWith("tokenIssuer");

							var subop8 = contractCall("tokenContract", TRANSFER_ABI,
									carolEthAddress, 500 * tokenMultiplier)
									.gas(250_000L)
									.payingWith("Bob");

							var subop9 = contractCallLocal("tokenContract", BALANCE_OF_ABI, aliceEthAddress)
									.gas(250_000L)
									.saveResultTo("aliceTokenBalance");

							var subop10 = contractCallLocal("tokenContract", BALANCE_OF_ABI, carolEthAddress)
									.gas(250_000L)
									.saveResultTo("carolTokenBalance");

							var subop11 = contractCallLocal("tokenContract", BALANCE_OF_ABI, bobEthAddress)
									.gas(250_000L)
									.saveResultTo("bobTokenBalance");

							CustomSpecAssert.allRunFor(spec, subop6, subop7, subop8, subop9, subop10, subop11);

							long aliceBalance = ((BigInteger) getValueFromRegistry(spec, "aliceTokenBalance",
									function)).longValue();
							long bobBalance = ((BigInteger) getValueFromRegistry(spec, "bobTokenBalance",
									function)).longValue();
							long carolBalance = ((BigInteger) getValueFromRegistry(spec, "carolTokenBalance",
									function)).longValue();

							ctxLog.info("aliceBalance  {}", aliceBalance / tokenMultiplier);
							ctxLog.info("bobBalance  {}", bobBalance / tokenMultiplier);
							ctxLog.info("carolBalance  {}", carolBalance / tokenMultiplier);

							Assertions.assertEquals(
									1000, aliceBalance / tokenMultiplier,
									"Alice's token balance should be 1_000");

							var subop12 = contractCall("tokenContract", APPROVE_ABI,
									daveEthAddress, 200 * tokenMultiplier)
									.gas(250_000L)
									.payingWith("Alice");

							var subop13 = contractCall("tokenContract", TRANSFER_FROM_ABI,
									aliceEthAddress, bobEthAddress, 100 * tokenMultiplier)
									.gas(250_000L)
									.payingWith("Dave");

							var subop14 = contractCallLocal("tokenContract", BALANCE_OF_ABI, aliceEthAddress)
									.gas(250_000L)
									.saveResultTo("aliceTokenBalance");

							var subop15 = contractCallLocal("tokenContract", BALANCE_OF_ABI, bobEthAddress)
									.gas(250_000L)
									.saveResultTo("bobTokenBalance");

							var subop16 = contractCallLocal("tokenContract", BALANCE_OF_ABI, carolEthAddress)
									.gas(250_000L)
									.saveResultTo("carolTokenBalance");

							var subop17 = contractCallLocal("tokenContract", BALANCE_OF_ABI, daveEthAddress)
									.gas(250_000L)
									.saveResultTo("daveTokenBalance");

							var subop18 = contractCallLocal("tokenContract", BALANCE_OF_ABI, issuerEthAddress)
									.gas(250_000L)
									.saveResultTo("issuerTokenBalance");

							CustomSpecAssert.allRunFor(spec, subop12, subop13, subop14, subop15, subop16, subop17,
									subop18);

							long daveBalance = ((BigInteger) getValueFromRegistry(spec, "daveTokenBalance",
									function)).longValue();
							aliceBalance = ((BigInteger) getValueFromRegistry(spec, "aliceTokenBalance",
									function)).longValue();
							bobBalance = ((BigInteger) getValueFromRegistry(spec, "bobTokenBalance",
									function)).longValue();
							carolBalance = ((BigInteger) getValueFromRegistry(spec, "carolTokenBalance",
									function)).longValue();
							issuerBalance = ((BigInteger) getValueFromRegistry(spec, "issuerTokenBalance",
									function)).longValue();

							ctxLog.info("aliceBalance at end {}", aliceBalance / tokenMultiplier);
							ctxLog.info("bobBalance at end {}", bobBalance / tokenMultiplier);
							ctxLog.info("carolBalance at end {}", carolBalance / tokenMultiplier);
							ctxLog.info("daveBalance at end {}", daveBalance / tokenMultiplier);
							ctxLog.info("issuerBalance at end {}", issuerBalance / tokenMultiplier);

							Assertions.assertEquals(
									997000, issuerBalance / tokenMultiplier,
									"TokenIssuer's final balance should be 997000");

							Assertions.assertEquals(
									900, aliceBalance / tokenMultiplier,
									"Alice's final balance should be 900");
							Assertions.assertEquals(
									1600, bobBalance / tokenMultiplier,
									"Bob's final balance should be 1600");
							Assertions.assertEquals(
									500, carolBalance / tokenMultiplier,
									"Carol's final balance should be 500");
							Assertions.assertEquals(
									0, daveBalance / tokenMultiplier,
									"Dave's final balance should be 0");
						})
				).then(
						assertionsHold((spec, ctxLog) -> {
							var finalOp = getContractRecords("tokenContract")
									.saveRecordNumToRegistry("tokenContractRecordNum")
									.hasCostAnswerPrecheck(OK);

							CustomSpecAssert.allRunFor(spec, finalOp);

							int totalRecordNum = spec.registry().getIntValue("tokenContractRecordNum");
							ctxLog.info("Finished {}", totalRecordNum);

							Assertions.assertEquals(
									0,
									totalRecordNum,
									"Contracts should no longer receive records!");
						})
				);
	}

	private <T> T getValueFromRegistry(HapiApiSpec spec, String from, CallTransaction.Function function) {
		byte[] value = spec.registry().getBytes(from);

		T decodedReturnedValue = null;
		Object[] retResults = function.decodeResult(value);
		if (retResults != null && retResults.length > 0) {
			decodedReturnedValue = (T) retResults[0];
		}
		return decodedReturnedValue;
	}


	HapiApiSpec smartContractInlineAssemblyCheck() {
		return defaultHapiSpec("smartContractInlineAssemblyCheck")
				.given(
						cryptoCreate("payer")
								.balance(10_000_000_000_000L),
						fileCreate("simpleStorageByteCode")
								.path(ContractResources.SIMPLE_STORAGE_BYTECODE_PATH),
						fileCreate("inlineTestByteCode")
								.path(ContractResources.INLINE_TEST_BYTECODE_PATH)

				).when(
						contractCreate("simpleStorageContract")
								.payingWith("payer")
								.gas(300_000L)
								.bytecode("simpleStorageByteCode")
								.via("simpleStorageContractTxn"),
						contractCreate("inlineTestContract")
								.payingWith("payer")
								.gas(300_000L)
								.bytecode("inlineTestByteCode")
								.via("inlineTestContractTxn")

				).then(
						assertionsHold((spec, ctxLog) -> {

							var subop1 = getContractInfo("simpleStorageContract")
									.nodePayment(10L)
									.saveToRegistry("simpleStorageKey");

							var subop2 = getAccountInfo("payer")
									.savingSnapshot("payerAccountInfo");
							CustomSpecAssert.allRunFor(spec, subop1, subop2);

							ContractGetInfoResponse.ContractInfo simpleStorageContractInfo = spec.registry().getContractInfo(
									"simpleStorageKey");
							String contractAddress = simpleStorageContractInfo.getContractAccountID();

							var subop3 = contractCallLocal("inlineTestContract", ContractResources.GET_CODE_SIZE_ABI,
									contractAddress)
									.saveResultTo("simpleStorageContractCodeSizeBytes")
									.gas(300_000L);

							CustomSpecAssert.allRunFor(spec, subop3);

							byte[] result = spec.registry().getBytes("simpleStorageContractCodeSizeBytes");

							String funcJson = ContractResources.GET_CODE_SIZE_ABI.replaceAll("'", "\"");
							CallTransaction.Function function = CallTransaction.Function.fromJsonInterface(funcJson);

							int codeSize = 0;
							if (result != null && result.length > 0) {
								Object[] retResults = function.decodeResult(result);
								if (retResults != null && retResults.length > 0) {
									BigInteger retBi = (BigInteger) retResults[0];
									codeSize = retBi.intValue();
								}
							}

							ctxLog.info("Contract code size {}", codeSize);
							Assertions.assertNotEquals(
									0, codeSize,
									"Real smart contract code size should be greater than 0");


							CryptoGetInfoResponse.AccountInfo payerAccountInfo = spec.registry().getAccountInfo("payerAccountInfo");
							String acctAddress = payerAccountInfo.getContractAccountID();

							var subop4 = contractCallLocal("inlineTestContract", ContractResources.GET_CODE_SIZE_ABI,
									acctAddress)
									.saveResultTo("fakeCodeSizeBytes")
									.gas(300_000L);

							CustomSpecAssert.allRunFor(spec, subop4);
							result = spec.registry().getBytes("fakeCodeSizeBytes");

							codeSize = 0;
							if (result != null && result.length > 0) {
								Object[] retResults = function.decodeResult(result);
								if (retResults != null && retResults.length > 0) {
									BigInteger retBi = (BigInteger) retResults[0];
									codeSize = retBi.intValue();
								}
							}

							ctxLog.info("Fake contract code size {}", codeSize);
							Assertions.assertEquals(
									0, codeSize,
									"Fake contract code size should be 0");
						})
				);
	}

	private HapiApiSpec multipleSelfDestructsAreSafe() {
		return defaultHapiSpec("MultipleSelfDestructsAreSafe")
				.given(
						fileCreate("bytecode").path(ContractResources.FUSE_BYTECODE_PATH),
						contractCreate("fuse").bytecode("bytecode")
				).when(
						contractCall("fuse", ContractResources.LIGHT_ABI).via("lightTxn")
				).then(
						getTxnRecord("lightTxn").logged()
				);
	}

	HapiApiSpec depositSuccess() {
		return defaultHapiSpec("DepositSuccess")
				.given(
						fileCreate("payableBytecode").path(ContractResources.PAYABLE_CONTRACT_BYTECODE_PATH),
						contractCreate("payableContract").bytecode("payableBytecode").adminKey(THRESHOLD)
				).when(
						contractCall("payableContract", ContractResources.DEPOSIT_ABI, depositAmount)
								.via("payTxn").sending(depositAmount)
				).then(
						getTxnRecord("payTxn")
								.logged()
								.hasPriority(recordWith().contractCallResult(
										resultWith().logs(inOrder()))));
	}

	HapiApiSpec multipleDepositSuccess() {
		return defaultHapiSpec("MultipleDepositSuccess")
				.given(
						fileCreate("payableBytecode").path(ContractResources.PAYABLE_CONTRACT_BYTECODE_PATH),
						contractCreate("payableContract").bytecode("payableBytecode").adminKey(THRESHOLD)
				)
				.when()
				.then(
						withOpContext((spec, opLog) -> {
							for (int i = 0; i < 10; i++) {
								var subOp1 = balanceSnapshot("payerBefore", "payableContract");
								var subOp2 = contractCall("payableContract", ContractResources.DEPOSIT_ABI,
										depositAmount)
										.via("payTxn").sending(depositAmount);
								var subOp3 = getAccountBalance("payableContract")
										.hasTinyBars(changeFromSnapshot("payerBefore", +depositAmount));
								CustomSpecAssert.allRunFor(spec, subOp1, subOp2, subOp3);
							}
						})
				);
	}

	HapiApiSpec depositDeleteSuccess() {
		long initBalance = 7890;
		return defaultHapiSpec("DepositDeleteSuccess")
				.given(
						cryptoCreate("beneficiary").balance(initBalance),
						fileCreate("payableBytecode").path(ContractResources.PAYABLE_CONTRACT_BYTECODE_PATH),
						contractCreate("payableContract").bytecode("payableBytecode").adminKey(THRESHOLD)
				).when(
						contractCall("payableContract", ContractResources.DEPOSIT_ABI, depositAmount)
								.via("payTxn").sending(depositAmount)

				).then(
						contractDelete("payableContract").transferAccount("beneficiary"),
						getAccountBalance("beneficiary")
								.hasTinyBars(initBalance + depositAmount)
				);
	}

	HapiApiSpec payableSuccess() {
		return defaultHapiSpec("PayableSuccess")
				.given(
						fileCreate("payableBytecode").path(ContractResources.PAYABLE_CONTRACT_BYTECODE_PATH),
						contractCreate("payableContract").bytecode("payableBytecode").adminKey(THRESHOLD)
				).when(
						contractCall("payableContract").via("payTxn").sending(depositAmount)
				).then(
						getTxnRecord("payTxn")
								.logged()
								.hasPriority(recordWith().contractCallResult(
										resultWith().logs(
												inOrder(
														logWith().longAtBytes(depositAmount, 24)))))
				);
	}

	HapiApiSpec simpleUpdate() {
		return defaultHapiSpec("SimpleUpdate")
				.given(
						fileCreate("simpleUpdateBytecode").path(ContractResources.SIMPLE_UPDATE)
				).when(
						contractCreate("simpleUpdateContract").bytecode("simpleUpdateBytecode").gas(1_000_000),
						contractCall("simpleUpdateContract", ContractResources.SIMPLE_UPDATE_ABI, 5, 42).gas(1_000_000),
						contractCall("simpleUpdateContract", ContractResources.SIMPLE_SELFDESTRUCT_UPDATE_ABI, "0x0000000000000000000000000000000000000002").gas(1_000_000)
				).then(
						contractCall("simpleUpdateContract", ContractResources.SIMPLE_UPDATE_ABI, 15, 434).gas(1_000_000).hasKnownStatus(CONTRACT_DELETED)
				);
	}

	HapiApiSpec vanillaSuccess() {
		return defaultHapiSpec("VanillaSuccess")
				.given(
						fileCreate("parentDelegateBytecode").path(ContractResources.DELEGATING_CONTRACT_BYTECODE_PATH),
						contractCreate("parentDelegate").bytecode("parentDelegateBytecode").adminKey(THRESHOLD),
						getContractInfo("parentDelegate").logged().saveToRegistry("parentInfo")
				).when(
						contractCall("parentDelegate", ContractResources.CREATE_CHILD_ABI).via("createChildTxn"),
						contractCall("parentDelegate", ContractResources.GET_CHILD_RESULT_ABI).via("getChildResultTxn"),
						contractCall("parentDelegate", ContractResources.GET_CHILD_ADDRESS_ABI).via(
								"getChildAddressTxn")
				).then(
						getTxnRecord("createChildTxn").logged()
								.saveCreatedContractListToRegistry("createChild")
								.logged(),
						getTxnRecord("getChildResultTxn").logged()
								.hasPriority(recordWith().contractCallResult(
										resultWith().resultThruAbi(
												ContractResources.GET_CHILD_RESULT_ABI,
												isLiteralResult(new Object[]{BigInteger.valueOf(7L)})))),
						getTxnRecord("getChildAddressTxn").logged()
								.hasPriority(recordWith().contractCallResult(
										resultWith()
												.resultThruAbi(
														ContractResources.GET_CHILD_ADDRESS_ABI,
														isContractWith(contractWith()
																.nonNullContractId()
																.propertiesInheritedFrom("parentInfo")))
												.logs(inOrder()))),
						contractListWithPropertiesInheritedFrom("createChildCallResult", 1, "parentInfo")
				);
	}

	HapiApiSpec insufficientGas() {
		return defaultHapiSpec("InsufficientGas")
				.given(
						fileCreate("simpleStorageBytecode")
								.path(ContractResources.SIMPLE_STORAGE_BYTECODE_PATH),
						contractCreate("simpleStorage").bytecode("simpleStorageBytecode").adminKey(THRESHOLD),
						getContractInfo("simpleStorage").saveToRegistry("simpleStorageInfo")
				).when().then(
						contractCall("simpleStorage", ContractResources.CREATE_CHILD_ABI).via("simpleStorageTxn")
								.gas(10L).hasKnownStatus(INSUFFICIENT_GAS),
						getTxnRecord("simpleStorageTxn").logged()
				);
	}

	HapiApiSpec insufficientFee() {
		return defaultHapiSpec("InsufficientFee")
				.given(
						cryptoCreate("accountToPay"),
						fileCreate("parentDelegateBytecode")
								.path(ContractResources.DELEGATING_CONTRACT_BYTECODE_PATH),
						contractCreate("parentDelegate").bytecode("parentDelegateBytecode")
				).when().then(
						contractCall("parentDelegate", ContractResources.CREATE_CHILD_ABI).fee(0L)
								.payingWith("accountToPay")
								.hasPrecheck(INSUFFICIENT_TX_FEE));
	}

	HapiApiSpec nonPayable() {
		return defaultHapiSpec("NonPayable")
				.given(
						fileCreate("parentDelegateBytecode")
								.path(ContractResources.DELEGATING_CONTRACT_BYTECODE_PATH),
						contractCreate("parentDelegate").bytecode("parentDelegateBytecode")
				).when(
						contractCall("parentDelegate", ContractResources.CREATE_CHILD_ABI).via("callTxn").sending(
										depositAmount)
								.hasKnownStatus(CONTRACT_REVERT_EXECUTED)
				).then(
						getTxnRecord("callTxn").logged().hasPriority(
								recordWith().contractCallResult(
										resultWith().logs(inOrder())))
				);
	}

	HapiApiSpec invalidContract() {
		String invalidContract = HapiSpecSetup.getDefaultInstance().invalidContractName();

		return defaultHapiSpec("InvalidContract")
				.given().when().then(
						contractCall(invalidContract, ContractResources.CREATE_CHILD_ABI)
								.hasKnownStatus(INVALID_CONTRACT_ID));
	}

	private HapiApiSpec resultSizeAffectsFees() {
		final long TRANSFER_AMOUNT = 1_000L;
		BiConsumer<TransactionRecord, Logger> RESULT_SIZE_FORMATTER = (record, txnLog) -> {
			ContractFunctionResult result = record.getContractCallResult();
			txnLog.info("Contract call result FeeBuilder size = "
						+ FeeBuilder.getContractFunctionSize(result)
						+ ", fee = " + record.getTransactionFee()
						+ ", result is [self-reported size = " + result.getContractCallResult().size()
						+ ", '" + result.getContractCallResult() + "']");
			txnLog.info("  Literally :: " + result);
		};

		return defaultHapiSpec("ResultSizeAffectsFees")
				.given(
						TxnVerbs.fileCreate("bytecode").path(ContractResources.VERBOSE_DEPOSIT_BYTECODE_PATH),
						TxnVerbs.contractCreate("testContract").bytecode("bytecode")
				).when(
						TxnVerbs.contractCall(
										"testContract", ContractResources.VERBOSE_DEPOSIT_ABI,
										TRANSFER_AMOUNT, 0, "So we out-danced thought...")
								.via("noLogsCallTxn").sending(TRANSFER_AMOUNT),
						TxnVerbs.contractCall(
										"testContract", ContractResources.VERBOSE_DEPOSIT_ABI,
										TRANSFER_AMOUNT, 5, "So we out-danced thought...")
								.via("loggedCallTxn").sending(TRANSFER_AMOUNT)

				).then(
						assertionsHold((spec, assertLog) -> {
							HapiGetTxnRecord noLogsLookup =
									QueryVerbs.getTxnRecord("noLogsCallTxn").loggedWith(RESULT_SIZE_FORMATTER);
							HapiGetTxnRecord logsLookup =
									QueryVerbs.getTxnRecord("loggedCallTxn").loggedWith(RESULT_SIZE_FORMATTER);
							allRunFor(spec, noLogsLookup, logsLookup);
							TransactionRecord unloggedRecord =
									noLogsLookup.getResponse().getTransactionGetRecord().getTransactionRecord();
							TransactionRecord loggedRecord =
									logsLookup.getResponse().getTransactionGetRecord().getTransactionRecord();
							assertLog.info("Fee for logged record   = " + loggedRecord.getTransactionFee());
							assertLog.info("Fee for unlogged record = " + unloggedRecord.getTransactionFee());
							Assertions.assertNotEquals(
									unloggedRecord.getTransactionFee(),
									loggedRecord.getTransactionFee(),
									"Result size should change the txn fee!");
						})
				);
	}

	HapiApiSpec smartContractFailFirst() {
		return defaultHapiSpec("smartContractFailFirst")
				.given(
						cryptoCreate("payer").balance(1_000_000_000_000L).logged(),
						fileCreate("bytecode")
								.path(ContractResources.SIMPLE_STORAGE_BYTECODE_PATH)
				).when(
						withOpContext((spec, ignore) -> {
							var subop1 = balanceSnapshot("balanceBefore0", "payer");

							var subop2 =
									contractCreate("failInsufficientGas")
											.balance(0)
											.payingWith("payer")
											.gas(10L)
											.bytecode("bytecode")
											.hasKnownStatus(INSUFFICIENT_GAS)
											.via("failInsufficientGas");

							var subop3 = getTxnRecord("failInsufficientGas").logged();
							allRunFor(spec, subop1, subop2, subop3);
							long delta = subop3.getResponseRecord().getTransactionFee();

							var subop4 = getAccountBalance("payer").hasTinyBars(
									changeFromSnapshot("balanceBefore0", -delta));
							allRunFor(spec, subop4);

						}),

						withOpContext((spec, ignore) -> {

							var subop1 = balanceSnapshot("balanceBefore1", "payer");

							var subop2 = contractCreate("failInvalidInitialBalance")
									.balance(100_000_000_000L)
									.payingWith("payer")
									.gas(250_000L)
									.bytecode("bytecode")
									.via("failInvalidInitialBalance")
									.hasKnownStatus(CONTRACT_REVERT_EXECUTED);

							var subop3 = getTxnRecord("failInvalidInitialBalance").logged();
							allRunFor(spec, subop1, subop2, subop3);
							long delta = subop3.getResponseRecord().getTransactionFee();

							var subop4 = getAccountBalance("payer").hasTinyBars(
									changeFromSnapshot("balanceBefore1", -delta));
							allRunFor(spec, subop4);

						}),

						withOpContext((spec, ignore) -> {

							var subop1 = balanceSnapshot("balanceBefore2", "payer");

							var subop2 = contractCreate("successWithZeroInitialBalance")
									.balance(0L)
									.payingWith("payer")
									.gas(250_000L)
									.bytecode("bytecode")
									.hasKnownStatus(SUCCESS)
									.via("successWithZeroInitialBalance");

							var subop3 = getTxnRecord("successWithZeroInitialBalance").logged();
							allRunFor(spec, subop1, subop2, subop3);
							long delta = subop3.getResponseRecord().getTransactionFee();

							var subop4 = getAccountBalance("payer").hasTinyBars(
									changeFromSnapshot("balanceBefore2", -delta));
							allRunFor(spec, subop4);

						}),

						withOpContext((spec, ignore) -> {

							var subop1 = balanceSnapshot("balanceBefore3", "payer");

							var subop2 = contractCall("successWithZeroInitialBalance",
									ContractResources.SIMPLE_STORAGE_SETTER_ABI, 999_999L)
									.payingWith("payer")
									.gas(300_000L)
									.hasKnownStatus(SUCCESS)
									.via("setValue");

							var subop3 = getTxnRecord("setValue").logged();
							allRunFor(spec, subop1, subop2, subop3);
							long delta = subop3.getResponseRecord().getTransactionFee();

							var subop4 = getAccountBalance("payer").hasTinyBars(
									changeFromSnapshot("balanceBefore3", -delta));
							allRunFor(spec, subop4);

						}),

						withOpContext((spec, ignore) -> {

							var subop1 = balanceSnapshot("balanceBefore4", "payer");

							var subop2 = contractCall("successWithZeroInitialBalance",
									ContractResources.SIMPLE_STORAGE_GETTER_ABI)
									.payingWith("payer")
									.gas(300_000L)
									.hasKnownStatus(SUCCESS)
									.via("getValue");

							var subop3 = getTxnRecord("getValue").logged();
							allRunFor(spec, subop1, subop2, subop3);
							long delta = subop3.getResponseRecord().getTransactionFee();

							var subop4 = getAccountBalance("payer").hasTinyBars(
									changeFromSnapshot("balanceBefore4", -delta));
							allRunFor(spec, subop4);

						})
				).then(
						getTxnRecord("failInsufficientGas").logged(),
						getTxnRecord("successWithZeroInitialBalance").logged(),
						getTxnRecord("failInvalidInitialBalance").logged()
				);
	}

	HapiApiSpec payTestSelfDestructCall() {
		return defaultHapiSpec("payTestSelfDestructCall")
				.given(
						cryptoCreate("payer").balance(1_000_000_000_000L).logged(),
						cryptoCreate("receiver").balance(1_000L),
						fileCreate("bytecode")
								.path(ContractResources.PAY_TEST_SELF_DESTRUCT_BYTECODE_PATH),
						contractCreate("payTestSelfDestruct")
								.bytecode("bytecode")
				).when(
						withOpContext((spec, opLog) -> {
							var subop1 = contractCall("payTestSelfDestruct", ContractResources.DEPOSIT_ABI, 1_000L)
									.payingWith("payer")
									.gas(300_000L)
									.via("deposit")
									.sending(1_000L);

							var subop2 = contractCall("payTestSelfDestruct", ContractResources.GET_BALANCE_ABI)
									.payingWith("payer")
									.gas(300_000L)
									.via("getBalance");

							AccountID contractAccountId = asId("payTestSelfDestruct", spec);
							var subop3 = contractCall("payTestSelfDestruct", ContractResources.KILL_ME_ABI,
									contractAccountId.getAccountNum())
									.payingWith("payer")
									.gas(300_000L)
									.hasKnownStatus(OBTAINER_SAME_CONTRACT_ID);

							var subop4 = contractCall("payTestSelfDestruct", ContractResources.KILL_ME_ABI, 999_999L)
									.payingWith("payer")
									.gas(300_000L)
									.hasKnownStatus(INVALID_SOLIDITY_ADDRESS);

							AccountID receiverAccountId = asId("receiver", spec);
							var subop5 = contractCall("payTestSelfDestruct", ContractResources.KILL_ME_ABI,
									receiverAccountId.getAccountNum())
									.payingWith("payer")
									.gas(300_000L)
									.via("selfDestruct")
									.hasKnownStatus(SUCCESS);

							CustomSpecAssert.allRunFor(spec, subop1, subop2, subop3, subop4, subop5);
						})
				).then(
						getTxnRecord("deposit").logged(),
						getTxnRecord("getBalance").logged()
								.hasPriority(recordWith().contractCallResult(
										resultWith().resultThruAbi(
												ContractResources.GET_BALANCE_ABI,
												isLiteralResult(new Object[] { BigInteger.valueOf(1_000L) })))),
						getAccountBalance("receiver")
								.hasTinyBars(2_000L)
				);
	}

	private HapiApiSpec contractTransferToSigReqAccountWithKeySucceeds() {
		return defaultHapiSpec("ContractTransferToSigReqAccountWithKeySucceeds")
				.given(
						cryptoCreate("contractCaller").balance(1_000_000_000_000L),
						cryptoCreate("receivableSigReqAccount")
								.balance(1_000_000_000_000L).receiverSigRequired(true),
						getAccountInfo("contractCaller").savingSnapshot("contractCallerInfo"),
						getAccountInfo("receivableSigReqAccount").savingSnapshot("receivableSigReqAccountInfo"),
						fileCreate("transferringContractBytecode").path(ContractResources.TRANSFERRING_CONTRACT)
				).when(
						contractCreate("transferringContract").bytecode("transferringContractBytecode")
								.gas(1_000_000L).balance(5000L)
				).then(
						withOpContext((spec, opLog) -> {
							String accountAddress = spec.registry()
									.getAccountInfo("receivableSigReqAccountInfo").getContractAccountID();
							Key receivableAccountKey = spec.registry()
									.getAccountInfo("receivableSigReqAccountInfo").getKey();
							Key contractCallerKey = spec.registry()
									.getAccountInfo("contractCallerInfo").getKey();
							spec.registry().saveKey("receivableKey", receivableAccountKey);
							spec.registry().saveKey("contractCallerKey", contractCallerKey);
							/* if any of the keys are missing, INVALID_SIGNATURE is returned */
							var call = contractCall("transferringContract",
									ContractResources.TRANSFERRING_CONTRACT_TRANSFERTOADDRESS,
									accountAddress, 1).payingWith("contractCaller").gas(300_000)
									.signedBy("receivableKey", "contractCallerKey").hasKnownStatus(SUCCESS);
							/* calling with the receivableSigReqAccount should pass without adding keys */
							var callWithReceivable = contractCall("transferringContract",
									ContractResources.TRANSFERRING_CONTRACT_TRANSFERTOADDRESS,
									accountAddress, 1).payingWith("receivableSigReqAccount")
									.gas(300_000).hasKnownStatus(SUCCESS);
							CustomSpecAssert.allRunFor(spec, call, callWithReceivable);
						})
				);
	}

	private HapiApiSpec contractTransferToSigReqAccountWithoutKeyFails() {
		return defaultHapiSpec("ContractTransferToSigReqAccountWithoutKeyFails")
				.given(
						cryptoCreate("receivableSigReqAccount")
								.balance(1_000_000_000_000L).receiverSigRequired(true),
						getAccountInfo("receivableSigReqAccount").savingSnapshot("receivableSigReqAccountInfo"),
						fileCreate("transferringContractBytecode").path(ContractResources.TRANSFERRING_CONTRACT)
				).when(
						contractCreate("transferringContract").bytecode("transferringContractBytecode")
								.gas(1_000_000L).balance(5000L)
				).then(
						withOpContext((spec, opLog) -> {
							String accountAddress = spec.registry()
									.getAccountInfo("receivableSigReqAccountInfo").getContractAccountID();
							var call = contractCall("transferringContract",
									ContractResources.TRANSFERRING_CONTRACT_TRANSFERTOADDRESS,
									accountAddress, 1).gas(300_000).hasKnownStatus(INVALID_SIGNATURE);
							CustomSpecAssert.allRunFor(spec, call);
						})
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
