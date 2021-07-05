package com.hedera.services.bdd.suites.token;

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.incompleteCustomFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fixedHbarFeeInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fixedHtsFeeInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fractionalFeeInSchedule;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_NOT_FULLY_SPECIFIED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FRACTION_DIVIDES_BY_ZERO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;

public class TokenFeeScheduleUpdateSpecs extends HapiApiSuite {

	private static final Logger log = LogManager.getLogger(TokenFeeScheduleUpdateSpecs.class);

	public static void main(String... args) {
		new TokenFeeScheduleUpdateSpecs().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[] {
						onlyValidCustomFeeScheduleCanBeUpdated(),
				}
		);
	}


	private HapiApiSpec onlyValidCustomFeeScheduleCanBeUpdated() {
		final var hbarAmount = 1_234L;
		final var htsAmount = 2_345L;
		final var numerator = 1;
		final var denominator = 10;
		final var minimumToCollect = 5;
		final var maximumToCollect = 50;

		final var token = "withCustomSchedules";
		final var feeDenom = "denom";
		final var hbarCollector = "hbarFee";
		final var htsCollector = "denomFee";
		final var tokenCollector = "fractionalFee";
		final var invalidEntityId = "1.2.786";

		final var adminKey = "admin";

		final var newHbarAmount = 17_234L;
		final var newHtsAmount = 27_345L;
		final var newNumerator = 17;
		final var newDenominator = 107;
		final var newMinimumToCollect = 57;
		final var newMaximumToCollect = 507;

		final var newFeeDenom = "newDenom";
		final var newHbarCollector = "newHbarFee";
		final var newHtsCollector = "newDenomFee";
		final var newTokenCollector = "newFractionalFee";

		return defaultHapiSpec("OnlyValidCustomFeeScheduleCanBeUpdated")
				.given(
						fileUpdate(APP_PROPERTIES)
								.payingWith(GENESIS)
								.overridingProps(Map.of("tokens.maxCustomFeesAllowed", "10")),
						newKeyNamed(adminKey),
						cryptoCreate(htsCollector),
						cryptoCreate(newHtsCollector),
						cryptoCreate(hbarCollector),
						cryptoCreate(newHbarCollector),
						cryptoCreate(tokenCollector),
						cryptoCreate(newTokenCollector),
						tokenCreate(feeDenom).treasury(htsCollector),
						tokenCreate(newFeeDenom).treasury(newHtsCollector),
						tokenCreate(token)
								.adminKey(adminKey)
								.treasury(tokenCollector)
								.withCustom(fixedHbarFee(hbarAmount, hbarCollector))
								.withCustom(fixedHtsFee(htsAmount, feeDenom, htsCollector))
								.withCustom(fractionalFee(
										numerator, denominator,
										minimumToCollect, OptionalLong.of(maximumToCollect),
										tokenCollector)),
						fileUpdate(APP_PROPERTIES)
								.payingWith(GENESIS)
								.overridingProps(Map.of("tokens.maxCustomFeesAllowed", "1"))
				)
				.when(
						tokenFeeScheduleUpdate(token)
								.withCustom(fractionalFee(
										numerator, 0,
										minimumToCollect, OptionalLong.of(maximumToCollect),
										tokenCollector))
								.hasKnownStatus(FRACTION_DIVIDES_BY_ZERO),
						tokenFeeScheduleUpdate(token)
								.withCustom(fractionalFee(
										-numerator, denominator,
										minimumToCollect, OptionalLong.of(maximumToCollect),
										tokenCollector))
								.hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE),
						tokenFeeScheduleUpdate(token)
								.withCustom(fractionalFee(
										numerator, denominator,
										-minimumToCollect, OptionalLong.of(maximumToCollect),
										tokenCollector))
								.hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE),
						tokenFeeScheduleUpdate(token)
								.withCustom(fractionalFee(
										numerator, denominator,
										minimumToCollect, OptionalLong.of(-maximumToCollect),
										tokenCollector))
								.hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE),
						tokenFeeScheduleUpdate(token)
								.withCustom(fixedHbarFee(hbarAmount, hbarCollector))
								.withCustom(fixedHtsFee(htsAmount, feeDenom, htsCollector))
								.hasKnownStatus(CUSTOM_FEES_LIST_TOO_LONG),
						tokenFeeScheduleUpdate(token)
								.withCustom(fixedHbarFee(hbarAmount, invalidEntityId))
								.hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR),
						tokenFeeScheduleUpdate(token)
								.withCustom(fixedHtsFee(htsAmount, invalidEntityId, htsCollector))
								.hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES),
						tokenFeeScheduleUpdate(token)
								.withCustom(fixedHtsFee(htsAmount, feeDenom, hbarCollector))
								.hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR),
						tokenFeeScheduleUpdate(token)
								.withCustom(fixedHtsFee(-htsAmount, feeDenom, htsCollector))
								.hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE),
						tokenFeeScheduleUpdate(token)
								.withCustom(incompleteCustomFee(hbarCollector))
								.hasKnownStatus(CUSTOM_FEE_NOT_FULLY_SPECIFIED),
						fileUpdate(APP_PROPERTIES)
								.payingWith(GENESIS)
								.overridingProps(Map.of("tokens.maxCustomFeesAllowed", "10")),
						tokenAssociate(newTokenCollector, token),
						tokenFeeScheduleUpdate(token)
								.withCustom(fixedHbarFee(newHbarAmount, newHbarCollector))
								.withCustom(fixedHtsFee(newHtsAmount, newFeeDenom, newHtsCollector))
								.withCustom(fractionalFee(
										newNumerator, newDenominator,
										newMinimumToCollect, OptionalLong.of(newMaximumToCollect),
										newTokenCollector))
				)
				.then(
						getTokenInfo(token)
								.hasCustom(fixedHbarFeeInSchedule(newHbarAmount, newHbarCollector))
								.hasCustom(fixedHtsFeeInSchedule(newHtsAmount, newFeeDenom, newHtsCollector))
								.hasCustom(fractionalFeeInSchedule(
										newNumerator, newDenominator,
										newMinimumToCollect, OptionalLong.of(newMaximumToCollect),
										newTokenCollector))
				);
	}



	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
