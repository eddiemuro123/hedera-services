package com.hedera.services.bdd.suites.token;

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


import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountNftInfos;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfos;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.queries.token.HapiTokenNftInfo.newTokenNftInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DOES_NOT_OWN_WIPED_NFT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_STILL_OWNS_NFTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_QUERY_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_BURN_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_BURN_METADATA;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_MINT_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_MINT_METADATA;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_WIPING_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_MAX_SUPPLY_REACHED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TREASURY_MUST_OWN_BURNED_NFT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

public class UniqueTokenManagementSpecs extends HapiApiSuite {
	private static final org.apache.logging.log4j.Logger log = LogManager.getLogger(UniqueTokenManagementSpecs.class);
	private static final String A_TOKEN = "TokenA";
	private static final String NFT = "nft";
	private static final String FUNGIBLE_TOKEN = "fungible";
	private static final String SUPPLY_KEY = "supplyKey";
	private static final String FIRST_USER = "Client1";
	private static final int BIGGER_THAN_LIMIT = 11;

	public static void main(String... args) {
		new UniqueTokenManagementSpecs().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[] {
				mintFailsWithLargeBatchSize(),
				mintFailsWithTooLongMetadata(),
				mintFailsWithInvalidMetadataFromBatch(),
				mintUniqueTokenHappyPath(),
				mintTokenWorksWhenAccountsAreFrozenByDefault(),
				mintFailsWithDeletedToken(),
				mintUniqueTokenWorksWithRepeatedMetadata(),
				mintDistinguishesFeeSubTypes(),
				mintUniqueTokenReceiptCheck(),
				mintUniqueTokenAssociatesAsExpected(),
				populatingMetadataForFungibleDoesNotWork(),
				populatingAmountForNonFungibleDoesNotWork(),
				finiteNftReachesMaxSupplyProperly(),

				burnHappyPath(),
				canOnlyBurnFromTreasury(),
				burnFailsOnInvalidSerialNumber(),
				burnRespectsBurnBatchConstraints(),
				treasuryBalanceCorrectAfterBurn(),
				burnWorksWhenAccountsAreFrozenByDefault(),
				serialNumbersOnlyOnFungibleBurnFails(),
				amountOnlyOnNonFungibleBurnFails(),

				getAccountNftInfosWorksWithMixedOwnerships(),
				failsWithAccountWithoutNfts(),
				validatesQueryOutOfRange(),
				getAccountNftInfosFailsWithInvalidQueryBoundaries(),
				getAccountNftInfosFailsWithDeletedAccount(),
				getAccountNftInfosFailsWithInexistentAccount(),

				wipeHappyPath(),
				wipeRespectsConstraints(),
				commonWipeFailsWhenInvokedOnUniqueToken(),
				uniqueWipeFailsWhenInvokedOnFungibleToken(),
				wipeFailsWithInvalidSerialNumber(),

				getTokenNftInfoWorks(),
				getTokenNftInfoFailsWithNoNft(),
				failsWithFungibleTokenGetNftInfos(),

				getTokenNftInfosAssociatesTokenNftInfosAsExpected(),
				getTokenNftInfosValidatesQueryRange(),
				getTokenNftInfosFailsWithTokenWithoutNfts(),
				getTokenNftInfosFailsWithInvalidQueryBoundaries(),
				getTokenNftInfosFailsWithDeletedTokenNft(),

				tokenDissociateHappyPath(),
				tokenDissociateFailsIfAccountOwnsUniqueTokens(),

				getAccountNftsInfoFailsWithDeletedAccount(),
				getAccountNftsInfoFailsWithInexistentAccount(),

				baseUniqueMintOperationIsChargedExpectedFee(),
				baseUniqueWipeOperationIsChargedExpectedFee(),
				baseUniqueBurnOperationIsChargedExpectedFee()
		});
	}

	private HapiApiSpec baseUniqueWipeOperationIsChargedExpectedFee() {
		final var uniqueToken = "nftType";
		final var wipeKey = "wipeKey";
		final var civilian = "civilian";
		final var wipeTxn = "wipeTxn";
		final var expectedNftWipePriceUsd = 0.001;

		return defaultHapiSpec("BaseUniqueWipeOperationIsChargedExpectedFee")
				.given(
						newKeyNamed(SUPPLY_KEY),
						newKeyNamed(wipeKey),
						cryptoCreate(civilian).key(wipeKey),
						cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS).key(wipeKey),
						tokenCreate(uniqueToken)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0L)
								.supplyKey(SUPPLY_KEY)
								.wipeKey(wipeKey)
								.treasury(TOKEN_TREASURY),
						tokenAssociate(civilian, uniqueToken),
						mintToken(uniqueToken,
								List.of(ByteString.copyFromUtf8("token_to_wipe"))),
						cryptoTransfer(movingUnique(uniqueToken, 1L)
								.between(TOKEN_TREASURY, civilian))
				)
				.when(
						wipeTokenAccount(uniqueToken, civilian, List.of(1L))
								.payingWith(TOKEN_TREASURY)
								.fee(ONE_HBAR)
								.blankMemo()
								.via(wipeTxn)
				).then(
						validateChargedUsdWithin(wipeTxn, expectedNftWipePriceUsd, 0.01)
				);
	}

	private HapiApiSpec populatingMetadataForFungibleDoesNotWork() {
		return defaultHapiSpec("PopulatingMetadataForFungibleDoesNotWork")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(FUNGIBLE_TOKEN)
								.initialSupply(0)
								.tokenType(TokenType.FUNGIBLE_COMMON)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(FUNGIBLE_TOKEN, List.of(
								metadata("some-data"),
								metadata("some-data2"),
								metadata("some-data3"),
								metadata("some-data4")
						)).hasKnownStatus(INVALID_TOKEN_MINT_AMOUNT).via("should-not-work")
				).then(
						getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 0),
						getTxnRecord("should-not-work").showsNoTransfers(),
						UtilVerbs.withOpContext((spec, opLog) -> {
							var mintNFT = getTxnRecord("should-not-work");
							allRunFor(spec, mintNFT);
							var receipt = mintNFT.getResponseRecord().getReceipt();
							Assert.assertEquals(0, receipt.getNewTotalSupply());
						})
				);
	}

	private HapiApiSpec populatingAmountForNonFungibleDoesNotWork() {
		return defaultHapiSpec("PopulatingAmountForNonFungibleDoesNotWork")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.initialSupply(0)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, 300).hasKnownStatus(INVALID_TOKEN_MINT_METADATA).via("should-not-work")
				).then(
						getTxnRecord("should-not-work").showsNoTransfers(),
						getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NFT, 0),
						UtilVerbs.withOpContext((spec, opLog) -> {
							var mintNFT = getTxnRecord("should-not-work");
							allRunFor(spec, mintNFT);
							var receipt = mintNFT.getResponseRecord().getReceipt();
							Assert.assertEquals(0, receipt.getNewTotalSupply());
							Assert.assertEquals(0, receipt.getSerialNumbersCount());
						})
				);
	}

	private HapiApiSpec finiteNftReachesMaxSupplyProperly() {
		return defaultHapiSpec("FiniteNftReachesMaxSupplyProperly")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.initialSupply(0)
								.maxSupply(3)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.FINITE)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, List.of(
								metadata("some-data"),
								metadata("some-data2"),
								metadata("some-data3"),
								metadata("some-data4")
						)).hasKnownStatus(TOKEN_MAX_SUPPLY_REACHED).via("should-not-appear")
				).then(
						getTxnRecord("should-not-appear").showsNoTransfers(),
						getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NFT, 0),
						UtilVerbs.withOpContext((spec, opLog) -> {
							var mintNFT = getTxnRecord("should-not-appear");
							allRunFor(spec, mintNFT);
							var receipt = mintNFT.getResponseRecord().getReceipt();
							Assert.assertEquals(0, receipt.getNewTotalSupply());
							Assert.assertEquals(0, receipt.getSerialNumbersCount());
						})
				);
	}

	private HapiApiSpec serialNumbersOnlyOnFungibleBurnFails() {
		return defaultHapiSpec("SerialNumbersOnlyOnFungibleBurnFails")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(FUNGIBLE_TOKEN)
								.initialSupply(0)
								.tokenType(FUNGIBLE_COMMON)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				)
				.when(
						mintToken(FUNGIBLE_TOKEN, 300)
				)
				.then(
						burnToken(FUNGIBLE_TOKEN, List.of(1L, 2L, 3L)).hasKnownStatus(INVALID_TOKEN_BURN_AMOUNT).via(
								"burn-failure"),
						getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 300),
						getTxnRecord("burn-failure").showsNoTransfers(),
						UtilVerbs.withOpContext((spec, opLog) -> {
							var burnTxn = getTxnRecord("burn-failure");
							allRunFor(spec, burnTxn);
							Assert.assertEquals(0, burnTxn.getResponseRecord().getReceipt().getNewTotalSupply());
						})
				);
	}

	private HapiApiSpec amountOnlyOnNonFungibleBurnFails() {
		return defaultHapiSpec("AmountOnlyOnNonFungibleBurnFails")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.initialSupply(0)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				)
				.when(
						mintToken(NFT, List.of(metadata("some-random-data"), metadata("some-other-random-data")))
				)
				.then(
						burnToken(NFT, 300).hasKnownStatus(INVALID_TOKEN_BURN_METADATA).via("burn-failure"),
						getTxnRecord("burn-failure").showsNoTransfers(),
						getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NFT, 2),
						UtilVerbs.withOpContext((spec, opLog) -> {
							var burnTxn = getTxnRecord("burn-failure");
							allRunFor(spec, burnTxn);
							Assert.assertEquals(0, burnTxn.getResponseRecord().getReceipt().getNewTotalSupply());
						})
				);
	}

	private HapiApiSpec mintUniqueTokenAssociatesAsExpected() {
		return defaultHapiSpec("MintUniqueTokenAssociatesAsExpected")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.initialSupply(0)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, List.of(
								metadata("some metadata"),
								metadata("some metadata2"),
								metadata("some metadata3")
						))
				).then(
						getAccountNftInfos(TOKEN_TREASURY, 0, 2)
								.hasNfts(
										newTokenNftInfo(NFT, 1, TOKEN_TREASURY, metadata("some metadata")),
										newTokenNftInfo(NFT, 2, TOKEN_TREASURY, metadata("some metadata2"))
								)
								.logged()
				);
	}

	private HapiApiSpec validatesQueryOutOfRange() {
		return defaultHapiSpec("ValidatesQueryOutOfRange")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.initialSupply(0)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, List.of(
								metadata("some metadata"),
								metadata("some metadata2"),
								metadata("some metadata3")
						))
				).then(
						getAccountNftInfos(TOKEN_TREASURY, 0, 6)
								.hasCostAnswerPrecheck(INVALID_QUERY_RANGE)
				);
	}

	private HapiApiSpec failsWithAccountWithoutNfts() {
		return defaultHapiSpec("FailsWithAccountWithoutNfts")
				.given(
						cryptoCreate(FIRST_USER)
				).when().then(
						getAccountNftInfos(FIRST_USER, 0, 2)
								.hasCostAnswerPrecheck(INVALID_QUERY_RANGE)
				);
	}

	private HapiApiSpec getAccountNftInfosWorksWithMixedOwnerships() {
		final var specialKey = "special";

		return defaultHapiSpec("GetAccountNftInfosWorksWithMixedOwnerships")
				.given(
						newKeyNamed(specialKey),
						cryptoCreate("oldTreasury").balance(0L),
						cryptoCreate("newTreasury").balance(0L),
						tokenCreate("tbu")
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.initialSupply(0L)
								.adminKey(specialKey)
								.supplyKey(specialKey)
								.treasury("oldTreasury"),
						tokenCreate("treasury-token-2")
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.initialSupply(0L)
								.adminKey(specialKey)
								.supplyKey(specialKey)
								.treasury("newTreasury"),
						tokenCreate("non-fungible-2")
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.initialSupply(0L)
								.adminKey(specialKey)
								.supplyKey(specialKey)
								.treasury("oldTreasury"),
						tokenCreate("non-fungible-3")
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.initialSupply(0L)
								.adminKey(specialKey)
								.supplyKey(specialKey)
								.treasury("oldTreasury")
				).when(
						mintToken("tbu", List.of(metadata("BLAMMO"))),
						mintToken("treasury-token-2", List.of(metadata("treasury-meta"))),
						mintToken("non-fungible-2", List.of(metadata("nft2-1"), metadata("nft2-2"),
								metadata("nft2-3"))),
						mintToken("non-fungible-3", List.of(metadata("nft3-1"), metadata("nft3-2"),
								metadata("nft3-3"))),
						getAccountInfo("oldTreasury").logged(),
						getAccountInfo("newTreasury").logged(),
						tokenAssociate("newTreasury", "tbu", "non-fungible-2", "non-fungible-3"),
						tokenUpdate("tbu")
								.treasury("newTreasury")
								.hasKnownStatus(SUCCESS),
						getTokenInfo("tbu").hasTreasury("newTreasury"),
						cryptoTransfer(
								movingUnique("non-fungible-2", 1, 2).between("oldTreasury", "newTreasury"),
								movingUnique("non-fungible-3", 1).between("oldTreasury", "newTreasury")).logged(),
						getAccountInfo("newTreasury").logged()
				).then(
						getAccountInfo("oldTreasury").logged(),
						getAccountInfo("newTreasury").logged(),
						getAccountNftInfos("newTreasury", 0, 5)
								.hasNfts(
										newTokenNftInfo("non-fungible-2", 1, "newTreasury", metadata("nft2-1")),
										newTokenNftInfo("non-fungible-2", 2, "newTreasury", metadata("nft2-2")),
										newTokenNftInfo("non-fungible-3", 1, "newTreasury", metadata("nft3-1")),
										newTokenNftInfo("tbu", 1, "newTreasury", metadata("BLAMMO")),
										newTokenNftInfo("treasury-token-2", 1, "newTreasury", metadata("treasury-meta"))
								)
								.logged(),
						getAccountNftInfos("newTreasury", 0, 4)
								.hasNfts(
										newTokenNftInfo("non-fungible-2", 1, "newTreasury", metadata("nft2-1")),
										newTokenNftInfo("non-fungible-2", 2, "newTreasury", metadata("nft2-2")),
										newTokenNftInfo("non-fungible-3", 1, "newTreasury", metadata("nft3-1")),
										newTokenNftInfo("tbu", 1, "newTreasury", metadata("BLAMMO"))
								)
								.logged(),
						getAccountNftInfos("newTreasury", 1, 4)
								.hasNfts(
										newTokenNftInfo("non-fungible-2", 2, "newTreasury", metadata("nft2-2")),
										newTokenNftInfo("non-fungible-3", 1, "newTreasury", metadata("nft3-1")),
										newTokenNftInfo("tbu", 1, "newTreasury", metadata("BLAMMO"))
								)
								.logged(),
						getTokenInfo("tbu").hasTreasury("newTreasury")
				);
	}

	private HapiApiSpec getAccountNftInfosFailsWithDeletedAccount() {
		return defaultHapiSpec("GetAccountNftInfosFailsWithDeletedAccount")
				.given(
						cryptoCreate(FIRST_USER),
						cryptoDelete(FIRST_USER)
				).when().then(
						getAccountNftInfos(FIRST_USER, 0, 2)
								.hasCostAnswerPrecheck(ACCOUNT_DELETED)
				);
	}

	private HapiApiSpec getAccountNftInfosFailsWithInexistentAccount() {
		return defaultHapiSpec("GetAccountNftInfosFailsWithInexistentAccount")
				.given().when().then(
						getAccountNftInfos("0.0.123", 0, 2)
								.hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
				);
	}

	private HapiApiSpec getAccountNftInfosFailsWithInvalidQueryBoundaries() {
		return defaultHapiSpec("GetAccountNftInfosFailsWithInvalidQueryBoundaries")
				.given(
						cryptoCreate(FIRST_USER)
				).when().then(
						getAccountNftInfos(FIRST_USER, 2, 0)
								.hasCostAnswerPrecheck(INVALID_QUERY_RANGE),
						getAccountNftInfos(FIRST_USER, 0, 100000000)
								.hasCostAnswerPrecheck(INVALID_QUERY_RANGE)
				);
	}

	private HapiApiSpec burnWorksWhenAccountsAreFrozenByDefault() {
		return defaultHapiSpec("BurnWorksWhenAccountsAreFrozenByDefault")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY),
						mintToken(NFT, List.of(metadata("memo")))
				)
				.when(
						burnToken(NFT, List.of(1L)).via("burnTxn").logged()
				)
				.then(
						getTxnRecord("burnTxn")
								.hasCostAnswerPrecheck(OK),
						getTokenNftInfo(NFT, 1)
								.hasCostAnswerPrecheck(INVALID_NFT_ID),
						getTokenNftInfos(NFT, 0, 1).hasCostAnswerPrecheck(INVALID_QUERY_RANGE),
						getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(0),
						getAccountNftInfos(TOKEN_TREASURY, 0, 1).hasCostAnswerPrecheck(INVALID_QUERY_RANGE)
				);
	}

	private HapiApiSpec burnFailsOnInvalidSerialNumber() {
		return defaultHapiSpec("BurnFailsOnInvalidSerialNumber")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY),
						mintToken(NFT, List.of(metadata("memo"))))
				.when()
				.then(
						burnToken(NFT, List.of(0L, 1L, 2L)).via("burnTxn").hasPrecheck(INVALID_NFT_ID),
						getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(1),
						getAccountNftInfos(TOKEN_TREASURY, 0, 1)
								.hasNfts(
										newTokenNftInfo(NFT, 1, TOKEN_TREASURY, metadata("memo"))
								)
								.logged()
				);
	}

	private HapiApiSpec burnRespectsBurnBatchConstraints() {
		return defaultHapiSpec("BurnRespectsBurnBatchConstraints")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY),
						mintToken(NFT, List.of(metadata("memo"))))
				.when(
				)
				.then(
						burnToken(NFT, LongStream.range(0, 1000).boxed().collect(Collectors.toList())).via("burnTxn")
								.hasPrecheck(BATCH_SIZE_LIMIT_EXCEEDED)
				);
	}

	private HapiApiSpec burnHappyPath() {
		return defaultHapiSpec("BurnHappyPath")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY),
						mintToken(NFT, List.of(metadata("memo")))
				).when(
						burnToken(NFT, List.of(1L)).via("burnTxn")
				).then(
						getTokenNftInfo(NFT, 1)
								.hasCostAnswerPrecheck(INVALID_NFT_ID),
						getTokenInfo(NFT)
								.hasTotalSupply(0),
						getAccountBalance(TOKEN_TREASURY)
								.hasTokenBalance(NFT, 0),
						getAccountInfo(TOKEN_TREASURY).hasToken(relationshipWith(NFT)).hasOwnedNfts(0)
				);
	}

	private HapiApiSpec canOnlyBurnFromTreasury() {
		final var nonTreasury = "anybodyElse";

		return defaultHapiSpec("CanOnlyBurnFromTreasury")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						cryptoCreate(nonTreasury),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY),
						mintToken(NFT, List.of(
								metadata("1"),
								metadata("2"))),
						tokenAssociate(nonTreasury, NFT),
						cryptoTransfer(movingUnique(NFT, 2L).between(TOKEN_TREASURY, nonTreasury))
				).when(
						burnToken(NFT, List.of(1L, 2L))
								.via("burnTxn")
								.hasKnownStatus(TREASURY_MUST_OWN_BURNED_NFT)
				).then(
						getTokenNftInfo(NFT, 1).hasSerialNum(1),
						getTokenNftInfo(NFT, 2).hasSerialNum(2),
						getTokenInfo(NFT).hasTotalSupply(2),
						getAccountBalance(nonTreasury).hasTokenBalance(NFT, 1),
						getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NFT, 1),
						getAccountInfo(nonTreasury).hasOwnedNfts(1),
						getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(1),
						getTokenNftInfos(NFT, 0, 2)
								.hasNfts(
										newTokenNftInfo(NFT, 1, TOKEN_TREASURY, metadata("1")),
										newTokenNftInfo(NFT, 2, nonTreasury, metadata("2"))
								).logged(),
						getAccountNftInfos(TOKEN_TREASURY, 0, 1)
								.hasNfts(
										newTokenNftInfo(NFT, 1, TOKEN_TREASURY, metadata("1"))
								),
						getAccountNftInfos(nonTreasury, 0, 1)
								.hasNfts(
										newTokenNftInfo(NFT, 2, nonTreasury, metadata("2"))
								)
				);
	}

	private HapiApiSpec treasuryBalanceCorrectAfterBurn() {
		return defaultHapiSpec("TreasuryBalanceCorrectAfterBurn")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY),
						mintToken(NFT,
								List.of(metadata("1"), metadata("2"), metadata("3"), metadata("4"), metadata("5")))
				)
				.when(
						burnToken(NFT, List.of(3L, 4L, 5L)).via("burnTxn")
				)
				.then(
						getTokenNftInfo(NFT, 1)
								.hasSerialNum(1)
								.hasCostAnswerPrecheck(OK),
						getTokenNftInfo(NFT, 2)
								.hasSerialNum(2)
								.hasCostAnswerPrecheck(OK),
						getTokenNftInfo(NFT, 3)
								.hasCostAnswerPrecheck(INVALID_NFT_ID),
						getTokenNftInfo(NFT, 4)
								.hasCostAnswerPrecheck(INVALID_NFT_ID),
						getTokenNftInfo(NFT, 5)
								.hasCostAnswerPrecheck(INVALID_NFT_ID),
						getTokenInfo(NFT)
								.hasTotalSupply(2),
						getAccountBalance(TOKEN_TREASURY)
								.hasTokenBalance(NFT, 2),
						getAccountInfo(TOKEN_TREASURY)
								.hasOwnedNfts(2),
						getTokenNftInfos(NFT, 0, 2)
								.hasNfts(
										newTokenNftInfo(NFT, 1, TOKEN_TREASURY, metadata("1")),
										newTokenNftInfo(NFT, 2, TOKEN_TREASURY, metadata("2"))
								)
								.logged(),
						getAccountNftInfos(TOKEN_TREASURY, 0, 2)
								.hasNfts(
										newTokenNftInfo(NFT, 1, TOKEN_TREASURY, metadata("1")),
										newTokenNftInfo(NFT, 2, TOKEN_TREASURY, metadata("2"))
								)
								.logged()
				);
	}

	private HapiApiSpec mintDistinguishesFeeSubTypes() {
		return defaultHapiSpec("MintDistinguishesFeeSubTypes")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						cryptoCreate("customPayer"),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY),
						tokenCreate(FUNGIBLE_TOKEN)
								.tokenType(TokenType.FUNGIBLE_COMMON)
								.supplyType(TokenSupplyType.FINITE)
								.initialSupply(10)
								.maxSupply(1100)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, List.of(metadata("memo"))).payingWith("customPayer").signedBy("customPayer",
								"supplyKey").via("mintNFT"),
						mintToken(FUNGIBLE_TOKEN, 100L).payingWith("customPayer").signedBy("customPayer",
								"supplyKey").via("mintFungible")
				).then(
						UtilVerbs.withOpContext((spec, opLog) -> {
							var mintNFT = getTxnRecord("mintNFT");
							var mintFungible = getTxnRecord("mintFungible");
							allRunFor(spec, mintNFT, mintFungible);
							var nftFee = mintNFT.getResponseRecord().getTransactionFee();
							var fungibleFee = mintFungible.getResponseRecord().getTransactionFee();
							Assert.assertNotEquals(
									"NFT Fee should NOT equal to the Fungible Fee!",
									nftFee,
									fungibleFee);
						})
				);
	}

	private HapiApiSpec mintFailsWithTooLongMetadata() {
		return defaultHapiSpec("MintFailsWithTooLongMetadata")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when().then(
						mintToken(NFT, List.of(
								metadataOfLength(101)
						)).hasPrecheck(ResponseCodeEnum.METADATA_TOO_LONG)
				);
	}

	private HapiApiSpec mintFailsWithInvalidMetadataFromBatch() {
		return defaultHapiSpec("MintFailsWithInvalidMetadataFromBatch")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when().then(
						mintToken(NFT, List.of(
								metadataOfLength(101),
								metadataOfLength(1)
						)).hasPrecheck(ResponseCodeEnum.METADATA_TOO_LONG)
				);
	}

	private HapiApiSpec mintFailsWithLargeBatchSize() {
		return defaultHapiSpec("MintFailsWithLargeBatchSize")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when().then(
						mintToken(NFT, batchOfSize(BIGGER_THAN_LIMIT))
								.hasPrecheck(BATCH_SIZE_LIMIT_EXCEEDED)
				);
	}

	private List<ByteString> batchOfSize(int size) {
		var batch = new ArrayList<ByteString>();
		for (int i = 0; i < size; i++) {
			batch.add(metadata("memo" + i));
		}
		return batch;
	}

	private ByteString metadataOfLength(int length) {
		return ByteString.copyFrom(genRandomBytes(length));
	}

	private ByteString metadata(String contents) {
		return ByteString.copyFromUtf8(contents);
	}

	private HapiApiSpec mintUniqueTokenHappyPath() {
		return defaultHapiSpec("MintUniqueTokenHappyPath")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyKey(SUPPLY_KEY)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT,
								List.of(metadata("memo"), metadata("memo1"))).via("mintTxn")
				).then(
						getReceipt("mintTxn").logged(),
						getTokenNftInfo(NFT, 1)
								.hasSerialNum(1)
								.hasMetadata(metadata("memo"))
								.hasTokenID(NFT)
								.hasAccountID(TOKEN_TREASURY)
								.hasValidCreationTime(),

						getTokenNftInfo(NFT, 2)
								.hasSerialNum(2)
								.hasMetadata(metadata("memo1"))
								.hasTokenID(NFT)
								.hasAccountID(TOKEN_TREASURY)
								.hasValidCreationTime(),

						getTokenNftInfo(NFT, 3)
								.hasCostAnswerPrecheck(INVALID_NFT_ID),

						getTokenNftInfos(NFT, 0, 1)
								.hasNfts(
										newTokenNftInfo(NFT, 1, TOKEN_TREASURY, metadata("memo"))
								)
								.logged(),

						getTokenNftInfos(NFT, 1, 2)
								.hasNfts(
										newTokenNftInfo(NFT, 2, TOKEN_TREASURY, metadata("memo1"))
								)
								.logged(),

						getTokenNftInfos(NFT, 0, 2)
								.hasNfts(
										newTokenNftInfo(NFT, 1, TOKEN_TREASURY, metadata("memo")),
										newTokenNftInfo(NFT, 2, TOKEN_TREASURY, metadata("memo1"))
								)
								.logged(),

						getAccountBalance(TOKEN_TREASURY)
								.hasTokenBalance(NFT, 2),

						getTokenInfo(NFT)
								.hasTreasury(TOKEN_TREASURY)
								.hasTotalSupply(2),

						getAccountInfo(TOKEN_TREASURY)
								.hasToken(relationshipWith(NFT)).hasOwnedNfts(2),

						getAccountNftInfos(TOKEN_TREASURY, 0, 2)
								.hasNfts(
										newTokenNftInfo(NFT, 1, TOKEN_TREASURY, metadata("memo")),
										newTokenNftInfo(NFT, 2, TOKEN_TREASURY, metadata("memo1"))
								)
								.logged()
				);
	}

	private HapiApiSpec mintTokenWorksWhenAccountsAreFrozenByDefault() {
		return defaultHapiSpec("MintTokenWorksWhenAccountsAreFrozenByDefault")
				.given(
						newKeyNamed(SUPPLY_KEY),
						newKeyNamed("tokenFreezeKey"),
						cryptoCreate(TOKEN_TREASURY).balance(0L),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyKey(SUPPLY_KEY)
								.freezeKey("tokenFreezeKey")
								.freezeDefault(true)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, List.of(metadata("memo")))
								.via("mintTxn")
				).then(
						getTokenNftInfo(NFT, 1)
								.hasTokenID(NFT)
								.hasAccountID(TOKEN_TREASURY)
								.hasMetadata(metadata("memo"))
								.hasValidCreationTime(),
						getTokenNftInfos(NFT, 0, 1)
								.hasNfts(
										newTokenNftInfo(NFT, 1, TOKEN_TREASURY, metadata("memo"))
								)
								.logged(),
						getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(1),
						getAccountNftInfos(TOKEN_TREASURY, 0, 1)
								.hasNfts(
										newTokenNftInfo(NFT, 1, TOKEN_TREASURY, metadata("memo"))
								)
								.logged()
				);
	}

	private HapiApiSpec mintFailsWithDeletedToken() {
		return defaultHapiSpec("MintFailsWithDeletedToken").given(
				newKeyNamed(SUPPLY_KEY),
				newKeyNamed("adminKey"),
				cryptoCreate(TOKEN_TREASURY),
				tokenCreate(NFT)
						.supplyKey(SUPPLY_KEY)
						.adminKey("adminKey")
						.treasury(TOKEN_TREASURY)
		).when(
				tokenDelete(NFT)
		).then(
				mintToken(NFT, List.of(metadata("memo")))
						.via("mintTxn")
						.hasKnownStatus(TOKEN_WAS_DELETED),

				getTokenNftInfo(NFT, 1)
						.hasCostAnswerPrecheck(INVALID_NFT_ID),

				getTokenInfo(NFT)
						.isDeleted()
		);
	}

	private HapiApiSpec getTokenNftInfoFailsWithNoNft() {
		return defaultHapiSpec("GetTokenNftInfoFailsWithNoNft")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY)
				)
				.when(
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY),
						mintToken(NFT, List.of(metadata("memo"))).via("mintTxn")
				)
				.then(
						getTokenNftInfo(NFT, 0)
								.hasCostAnswerPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
						getTokenNftInfo(NFT, -1)
								.hasCostAnswerPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
						getTokenNftInfo(NFT, 2)
								.hasCostAnswerPrecheck(INVALID_NFT_ID)
				);
	}

	private HapiApiSpec getTokenNftInfoWorks() {
		return defaultHapiSpec("GetTokenNftInfoWorks")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY)
				).when(
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY),
						mintToken(NFT, List.of(metadata("memo")))
				).then(
						getTokenNftInfo(NFT, 0)
								.hasCostAnswerPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
						getTokenNftInfo(NFT, -1)
								.hasCostAnswerPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
						getTokenNftInfo(NFT, 2)
								.hasCostAnswerPrecheck(INVALID_NFT_ID),
						getTokenNftInfo(NFT, 1)
								.hasTokenID(NFT)
								.hasAccountID(TOKEN_TREASURY)
								.hasMetadata(metadata("memo"))
								.hasSerialNum(1)
								.hasValidCreationTime()
				);
	}

	private HapiApiSpec mintUniqueTokenWorksWithRepeatedMetadata() {
		return defaultHapiSpec("MintUniqueTokenWorksWithRepeatedMetadata")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, List.of(metadata("memo"), metadata("memo")))
								.via("mintTxn")
				).then(
						getTokenNftInfo(NFT, 1)
								.hasSerialNum(1)
								.hasMetadata(metadata("memo"))
								.hasAccountID(TOKEN_TREASURY)
								.hasTokenID(NFT)
								.hasValidCreationTime(),

						getTokenNftInfo(NFT, 2)
								.hasSerialNum(2)
								.hasMetadata(metadata("memo"))
								.hasAccountID(TOKEN_TREASURY)
								.hasTokenID(NFT)
								.hasValidCreationTime(),
						getTokenNftInfos(NFT, 0, 2)
								.hasNfts(
										newTokenNftInfo(NFT, 1, TOKEN_TREASURY, metadata("memo")),
										newTokenNftInfo(NFT, 2, TOKEN_TREASURY, metadata("memo"))
								)
								.logged(),
						getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(2),
						getAccountNftInfos(TOKEN_TREASURY, 0, 2)
								.hasNfts(
										newTokenNftInfo(NFT, 1, TOKEN_TREASURY, metadata("memo")),
										newTokenNftInfo(NFT, 2, TOKEN_TREASURY, metadata("memo"))
								)
								.logged()
				);
	}

	private HapiApiSpec wipeHappyPath() {
		return defaultHapiSpec("WipeHappyPath")
				.given(
						newKeyNamed(SUPPLY_KEY),
						newKeyNamed("wipeKey"),
						newKeyNamed("treasuryKey"),
						newKeyNamed("accKey"),
						cryptoCreate(TOKEN_TREASURY).key("treasuryKey"),
						cryptoCreate("account").key("accKey"),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY)
								.wipeKey("wipeKey"),
						tokenAssociate("account", NFT),
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo"), ByteString.copyFromUtf8("memo2"))),
						cryptoTransfer(
								movingUnique(NFT, 2L).between(TOKEN_TREASURY, "account")
						)
				)
				.when(
						wipeTokenAccount(NFT, "account", List.of(2L)).via("wipeTxn")
				)
				.then(
						getAccountInfo("account").hasOwnedNfts(0),
						getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(1),
						getTokenInfo(NFT).hasTotalSupply(1),
						getTokenNftInfo(NFT, 2).hasCostAnswerPrecheck(INVALID_NFT_ID),
						getTokenNftInfo(NFT, 1).hasSerialNum(1),
						wipeTokenAccount(NFT, "account", List.of(1L))
								.hasKnownStatus(ACCOUNT_DOES_NOT_OWN_WIPED_NFT)
				);
	}

	private HapiApiSpec wipeRespectsConstraints() {
		return defaultHapiSpec("WipeRespectsConstraints").given(

				newKeyNamed(SUPPLY_KEY),
				newKeyNamed("wipeKey"),
				cryptoCreate(TOKEN_TREASURY),
				cryptoCreate("account"),
				tokenCreate(NFT)
						.tokenType(NON_FUNGIBLE_UNIQUE)
						.supplyType(TokenSupplyType.INFINITE)
						.supplyKey(SUPPLY_KEY)
						.initialSupply(0)
						.treasury(TOKEN_TREASURY)
						.wipeKey("wipeKey"),
				tokenAssociate("account", NFT),
				mintToken(NFT, List.of(
						metadata("memo"),
						metadata("memo2")))
						.via("mintTxn"),
				cryptoTransfer(
						movingUnique(NFT, 1, 2).between(TOKEN_TREASURY, "account")
				)
		).when(
				wipeTokenAccount(NFT, "account", LongStream.range(0, 1000).boxed().collect(Collectors.toList()))
						.hasPrecheck(BATCH_SIZE_LIMIT_EXCEEDED),
				getAccountNftInfos("account", 0, 2).hasNfts(
						newTokenNftInfo(NFT, 1, "account", metadata("memo")),
						newTokenNftInfo(NFT, 2, "account", metadata("memo2"))
				)
		).then();
	}

	private HapiApiSpec commonWipeFailsWhenInvokedOnUniqueToken() {
		return defaultHapiSpec("CommonWipeFailsWhenInvokedOnUniqueToken")
				.given(
						newKeyNamed(SUPPLY_KEY),
						newKeyNamed("wipeKey"),
						cryptoCreate(TOKEN_TREASURY),
						cryptoCreate("account"),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY)
								.wipeKey("wipeKey"),
						tokenAssociate("account", NFT),
						mintToken(NFT, List.of(metadata("memo"))),
						cryptoTransfer(
								movingUnique(NFT, 1).between(TOKEN_TREASURY, "account")
						)
				).when()
				.then(
						wipeTokenAccount(NFT, "account", 1L)
								.hasKnownStatus(FAIL_INVALID)
								.via("wipeTxn"),
						// no new totalSupply
						getTokenInfo(NFT).hasTotalSupply(1),
						// no tx record
						getTxnRecord("wipeTxn").showsNoTransfers(),
						//verify balance not decreased
						getAccountInfo("account").hasOwnedNfts(1),
						getAccountBalance("account").hasTokenBalance(NFT, 1)
				);
	}

	private HapiApiSpec uniqueWipeFailsWhenInvokedOnFungibleToken() { // invokes unique wipe on fungible tokens
		return defaultHapiSpec("UniqueWipeFailsWhenInvokedOnFungibleToken")
				.given(
						newKeyNamed("wipeKey"),
						cryptoCreate(TOKEN_TREASURY),
						cryptoCreate("account"),
						tokenCreate(A_TOKEN)
								.tokenType(TokenType.FUNGIBLE_COMMON)
								.initialSupply(10)
								.treasury(TOKEN_TREASURY)
								.wipeKey("wipeKey"),
						tokenAssociate("account", A_TOKEN),
						cryptoTransfer(
								TokenMovement.moving(5, A_TOKEN).between(TOKEN_TREASURY, "account")
						)
				)
				.when(
						wipeTokenAccount(A_TOKEN, "account", List.of(1L, 2L))
								.hasKnownStatus(INVALID_WIPING_AMOUNT)
								.via("wipeTx")
				)
				.then(
						getTokenInfo(A_TOKEN).hasTotalSupply(10),
						getTxnRecord("wipeTx").showsNoTransfers(),
						getAccountBalance("account").hasTokenBalance(A_TOKEN, 5)
				);
	}

	private HapiApiSpec wipeFailsWithInvalidSerialNumber() {
		return defaultHapiSpec("WipeFailsWithInvalidSerialNumber")
				.given(
						newKeyNamed(SUPPLY_KEY),
						newKeyNamed("wipeKey"),
						cryptoCreate(TOKEN_TREASURY),
						cryptoCreate("account"),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY)
								.wipeKey("wipeKey"),
						tokenAssociate("account", NFT),
						mintToken(NFT, List.of(
								metadata("memo"),
								metadata("memo")))
								.via("mintTxn")
				).when().then(
						wipeTokenAccount(NFT, "account", List.of(-5L, -6L)).hasPrecheck(INVALID_NFT_ID)
				);
	}

	private HapiApiSpec mintUniqueTokenReceiptCheck() {
		return defaultHapiSpec("mintUniqueTokenReceiptCheck")
				.given(
						cryptoCreate(TOKEN_TREASURY),
						cryptoCreate(FIRST_USER),
						newKeyNamed("supplyKey"),
						tokenCreate(A_TOKEN)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.initialSupply(0)
								.supplyKey("supplyKey")
								.treasury(TOKEN_TREASURY)
				)
				.when(
						mintToken(A_TOKEN, List.of(metadata("memo"))).via("mintTransferTxn")
				)
				.then(
						UtilVerbs.withOpContext((spec, opLog) -> {
							var mintNft = getTxnRecord("mintTransferTxn");
							allRunFor(spec, mintNft);
							var tokenTransferLists = mintNft.getResponseRecord().getTokenTransferListsList();
							Assert.assertEquals(1, tokenTransferLists.size());
							tokenTransferLists.stream().forEach(tokenTransferList -> {
								Assert.assertEquals(1, tokenTransferList.getNftTransfersList().size());
								tokenTransferList.getNftTransfersList().stream().forEach(nftTransfers -> {
									Assert.assertEquals(AccountID.getDefaultInstance(),
											nftTransfers.getSenderAccountID());
									Assert.assertEquals(TxnUtils.asId(TOKEN_TREASURY, spec),
											nftTransfers.getReceiverAccountID());
									Assert.assertEquals(1L, nftTransfers.getSerialNumber());
								});
							});
						}),
						getTxnRecord("mintTransferTxn").logged(),
						getReceipt("mintTransferTxn").logged()
				);
	}

	private HapiApiSpec getTokenNftInfosAssociatesTokenNftInfosAsExpected() {
		return defaultHapiSpec("GetTokenNftInfosAssociatesTokenNftInfosAsExpected")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.initialSupply(0)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, List.of(
								metadata("some metadata"),
								metadata("some metadata2"),
								metadata("some metadata3")
						))
				).then(
						getTokenNftInfos(NFT, 0, 3)
								.hasNfts(
										newTokenNftInfo(NFT, 1, TOKEN_TREASURY, metadata("some metadata")),
										newTokenNftInfo(NFT, 2, TOKEN_TREASURY, metadata("some metadata2")),
										newTokenNftInfo(NFT, 3, TOKEN_TREASURY, metadata("some metadata3"))
								)
								.logged()
				);
	}

	private HapiApiSpec getTokenNftInfosValidatesQueryRange() {
		return defaultHapiSpec("GetTokenNftInfosValidatesQueryRange")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.initialSupply(0)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, List.of(
								metadata("some metadata"),
								metadata("some metadata2"),
								metadata("some metadata3")
						))
				).then(
						getTokenNftInfos(NFT, 0, 6)
								.hasCostAnswerPrecheck(INVALID_QUERY_RANGE)
				);
	}

	private HapiApiSpec getTokenNftInfosFailsWithTokenWithoutNfts() {
		return defaultHapiSpec("GetTokenNftInfosFailsWithTokenWithoutNfts")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.initialSupply(0)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when()
				.then(
						getTokenNftInfos(NFT, 0, 2)
								.hasCostAnswerPrecheck(INVALID_QUERY_RANGE)
				);
	}

	private HapiApiSpec getTokenNftInfosFailsWithInvalidQueryBoundaries() {
		return defaultHapiSpec("GetTokenNftInfosFailsWithInvalidQueryBoundaries")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.initialSupply(0)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when().then(
						getTokenNftInfos(NFT, 2, 0)
								.hasCostAnswerPrecheck(INVALID_QUERY_RANGE),
						getTokenNftInfos(NFT, 0, 100000000)
								.hasCostAnswerPrecheck(INVALID_QUERY_RANGE)
				);
	}

	private HapiApiSpec getTokenNftInfosFailsWithDeletedTokenNft() {
		return defaultHapiSpec("GetTokenNftInfosFailsWithDeletedTokenNft")
				.given(
						newKeyNamed(SUPPLY_KEY),
						newKeyNamed("nftAdmin"),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.initialSupply(0)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.adminKey("nftAdmin")
								.treasury(TOKEN_TREASURY)
				).when(
						tokenDelete(NFT)
				)
				.then(
						getTokenNftInfos(NFT, 0, 2)
								.hasCostAnswerPrecheck(TOKEN_WAS_DELETED)
				);
	}

	public HapiApiSpec failsWithFungibleTokenGetNftInfos() {
		return defaultHapiSpec("FailsWithFungibleTokenGetNftInfos")
				.given(
						cryptoCreate(TOKEN_TREASURY).balance(0L)
				).when(
						tokenCreate(A_TOKEN)
								.initialSupply(1_000)
								.treasury(TOKEN_TREASURY)
				).then(
						getTokenNftInfos(A_TOKEN, 0, 2)
								.hasCostAnswerPrecheck(NOT_SUPPORTED)
				);
	}

	private HapiApiSpec getAccountNftsInfoFailsWithDeletedAccount() {
		return defaultHapiSpec("GetAccountNftsInfoFailsWithDeletedAccount")
				.given(
						cryptoCreate(FIRST_USER),
						cryptoDelete(FIRST_USER)
				).when().then(
						getAccountNftInfos(FIRST_USER, 0, 2)
								.hasCostAnswerPrecheck(ACCOUNT_DELETED)
				);
	}

	private HapiApiSpec getAccountNftsInfoFailsWithInexistentAccount() {
		return defaultHapiSpec("GetAccountNftsInfoFailsWithInexistentAccount")
				.given().when().then(
						getAccountNftInfos("0.0.123", 0, 2)
								.hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
				);
	}

	private HapiApiSpec tokenDissociateHappyPath() {
		return defaultHapiSpec("tokenDissociateHappyPath")
				.given(

						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						cryptoCreate("acc"),
						tokenCreate(NFT)
								.initialSupply(0)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				)
				.when(
						tokenAssociate("acc", NFT)
				)
				.then(
						tokenDissociate("acc", NFT).hasKnownStatus(SUCCESS),
						getAccountInfo("acc").hasNoTokenRelationship(NFT)
				);
	}

	private HapiApiSpec tokenDissociateFailsIfAccountOwnsUniqueTokens() {
		return defaultHapiSpec("tokenDissociateFailsIfAccountOwnsUniqueTokens")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						cryptoCreate("acc"),
						tokenCreate(NFT)
								.initialSupply(0)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.treasury(TOKEN_TREASURY)
				).when(
						tokenAssociate("acc", NFT),
						mintToken(NFT, List.of(metadata("memo1"), metadata("memo2")))
				).then(
						cryptoTransfer(TokenMovement.movingUnique(NFT, 1, 2).between(TOKEN_TREASURY, "acc")),
						tokenDissociate("acc", NFT).hasKnownStatus(ACCOUNT_STILL_OWNS_NFTS)
				);
	}

	private HapiApiSpec baseUniqueMintOperationIsChargedExpectedFee() {
		final var uniqueToken = "nftType";
		final var supplyKey = "mint!";
		final var civilianPayer = "civilian";
		final var standard100ByteMetadata = ByteString.copyFromUtf8(
				"0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789");
		final var baseTxn = "baseTxn";
		final var expectedNftMintPriceUsd = 0.001;

		return defaultHapiSpec("BaseUniqueMintOperationIsChargedExpectedFee")
				.given(
						newKeyNamed(supplyKey),
						cryptoCreate(civilianPayer).key(supplyKey),
						tokenCreate(uniqueToken)
								.initialSupply(0L)
								.expiry(Instant.now().getEpochSecond() + THREE_MONTHS_IN_SECONDS)
								.supplyKey(supplyKey)
								.tokenType(NON_FUNGIBLE_UNIQUE)
				)
				.when(
						mintToken(uniqueToken, List.of(standard100ByteMetadata))
								.payingWith(civilianPayer)
								.signedBy(supplyKey)
								.blankMemo()
								.via(baseTxn)
				).then(
						validateChargedUsdWithin(baseTxn, expectedNftMintPriceUsd, 0.01)
				);
	}

	private HapiApiSpec baseUniqueBurnOperationIsChargedExpectedFee() {
		final var uniqueToken = "nftType";
		final var supplyKey = "burn!";
		final var civilianPayer = "civilian";
		final var baseTxn = "baseTxn";
		final var expectedNftBurnPriceUsd = 0.001;

		return defaultHapiSpec("BaseUniqueBurnOperationIsChargedExpectedFee")
				.given(
						newKeyNamed(supplyKey),
						cryptoCreate(civilianPayer).key(supplyKey),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(uniqueToken)
								.initialSupply(0)
								.supplyKey(supplyKey)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.treasury(TOKEN_TREASURY),
						mintToken(uniqueToken, List.of(metadata("memo")))
				)
				.when(
						burnToken(uniqueToken, List.of(1L))
								.fee(ONE_HBAR)
								.payingWith(civilianPayer)
								.blankMemo()
								.via(baseTxn)
				).then(
						validateChargedUsdWithin(baseTxn, expectedNftBurnPriceUsd, 0.01)
				);
	}


	protected Logger getResultsLogger() {
		return log;
	}

	private byte[] genRandomBytes(int numBytes) {
		byte[] contents = new byte[numBytes];
		(new Random()).nextBytes(contents);
		return contents;
	}
}