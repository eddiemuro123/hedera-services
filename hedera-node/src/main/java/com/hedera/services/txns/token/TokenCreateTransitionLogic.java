package com.hedera.services.txns.token;

/*-
 * ‌
 * Hedera Services Node
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

import com.hedera.services.context.TransactionContext;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.state.enums.TokenType;
import com.hedera.services.store.AccountStore;
import com.hedera.services.store.TypedTokenStore;
import com.hedera.services.store.models.Account;
import com.hedera.services.store.models.Id;
import com.hedera.services.store.models.Token;
import com.hedera.services.store.models.TokenRelationship;
import com.hedera.services.store.models.fees.CustomFee;
import com.hedera.services.store.models.fees.FixedFee;
import com.hedera.services.store.models.fees.FractionalFee;
import com.hedera.services.txns.TransitionLogic;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.utils.TokenTypesMapper;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenCreateTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.hedera.services.exceptions.ValidationUtils.validateFalse;
import static com.hedera.services.exceptions.ValidationUtils.validateTrue;
import static com.hedera.services.txns.validation.TokenListChecks.checkKeys;
import static com.hedera.services.txns.validation.TokenListChecks.suppliesCheck;
import static com.hedera.services.txns.validation.TokenListChecks.supplyTypeCheck;
import static com.hedera.services.txns.validation.TokenListChecks.typeCheck;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ROYALTY_FRACTION_CANNOT_EXCEED_ONE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

/**
 * Provides the state transition for token creation.
 */
public class TokenCreateTransitionLogic implements TransitionLogic {
	private final OptionValidator validator;
	private final TransactionContext txnCtx;
	private final GlobalDynamicProperties dynamicProperties;
	private final Function<TransactionBody, ResponseCodeEnum> SEMANTIC_CHECK = this::validate;
	private final EntityIdSource ids;
	private final TypedTokenStore typedTokenStore;
	private final AccountStore accountStore;

	public TokenCreateTransitionLogic(
			OptionValidator validator,
			TypedTokenStore typedTokenStore,
			AccountStore accountStore,
			TransactionContext txnCtx,
			GlobalDynamicProperties dynamicProperties,
			EntityIdSource ids
	) {
		this.validator = validator;
		this.txnCtx = txnCtx;
		this.dynamicProperties = dynamicProperties;
		this.ids = ids;
		this.accountStore = accountStore;
		this.typedTokenStore = typedTokenStore;
	}

	@Override
	public void doStateTransition() {
		/* --- Translate from gRPC types --- */
		final var op = txnCtx.accessor().getTxn().getTokenCreation();
		final var treasuryGrpcId = op.getTreasury();
		validateExpiry(op);

		/* --- Load model objects --- */
		final var treasury = accountStore.loadAccountOrFailWith(Id.fromGrpcAccount(treasuryGrpcId),
				INVALID_TREASURY_ACCOUNT_FOR_TOKEN);
		Account autoRenewModel = null;
		if (op.hasAutoRenewAccount()) {
			final var autoRenewGrpcId = op.getAutoRenewAccount();
			autoRenewModel = accountStore.loadAccountOrFailWith(Id.fromGrpcAccount(autoRenewGrpcId),
					INVALID_AUTORENEW_ACCOUNT);
		}

		/* --- Create the token --- */
		final var tokenId = ids.newTokenId(txnCtx.activePayer());
		final var id = Id.fromGrpcToken(tokenId);
		final var customFeesList = initCustomFees(op, id);
		final var created = Token.fromGrpcTokenCreate(
				id, op, treasury, autoRenewModel, customFeesList, txnCtx.consensusTime().getEpochSecond());
		final var relationsToPersist = updateAccountRelations(created, treasury, customFeesList);

		if (op.getInitialSupply() > 0) {
			created.mint(relationsToPersist.get(0), op.getInitialSupply(), true);
		}

		/* --- Persist anything modified/new --- */
		for (final var rel : relationsToPersist) {
			accountStore.persistAccount(rel.getAccount());
		}
		typedTokenStore.persistNew(created);
		typedTokenStore.persistTokenRelationships(relationsToPersist);
	}

	/**
	 * Associates the created token with the treasury and all the fee collectors.
	 * Note: the first returned {@link TokenRelationship} is always the treasury rel.
	 *
	 * @param created
	 * 		the token created in the transition
	 * @param treasury
	 * 		treasury account for the token
	 * @param customFees
	 * 		a list of valid custom fees to be applied
	 * @return list of formed relationships between the token and the account.
	 */
	private List<TokenRelationship> updateAccountRelations(Token created, Account treasury,
			List<CustomFee> customFees) {
		final var relations = new ArrayList<TokenRelationship>();
		final var associatedAccounts = new HashSet<Id>();

		final var treasuryRel = created.newEnabledRelationship(treasury);
		treasury.associateWith(List.of(created), dynamicProperties.maxTokensPerAccount());
		relations.add(treasuryRel);
		associatedAccounts.add(treasury.getId());

		for (var fee : customFees) {
			if (fee.shouldCollectorBeAutoAssociated()) {
				final var collector = fee.getCollector();
				if (!associatedAccounts.contains(collector.getId())) {
					final var collectorRelation = created.newEnabledRelationship(collector);
					if (!collector.getAssociatedTokens().contains(created.getId())) {
						collector.associateWith(List.of(created), dynamicProperties.maxTokensPerAccount());
					}
					relations.add(collectorRelation);
				}
			}
		}

		return relations;
	}

	private void validateExpiry(TokenCreateTransactionBody op) {
		validateFalse(op.hasExpiry() && !validator.isValidExpiry(op.getExpiry()), INVALID_EXPIRATION_TIME);
	}

	/**
	 * Validates custom fees, extracting them from the transaction body and treating
	 * each different grpc fee as a separate model - a {@link CustomFee}, which holds a
	 * {@link FractionalFee}, {@link FixedFee}, or {@link com.hedera.services.store.models.fees.RoyaltyFee}
	 * Fees are being validated at the moment of their instantiation.
	 *
	 * @param op
	 * 		transaction body which contains the list of fees
	 * @param provisionalId
	 * 		the token which is being created
	 */
	private List<CustomFee> initCustomFees(TokenCreateTransactionBody op, Id provisionalId) {
		final var tooManyCustomFees = op.getCustomFeesCount() > dynamicProperties.maxCustomFeesAllowed();
		validateFalse(tooManyCustomFees, CUSTOM_FEES_LIST_TOO_LONG);

		final var customFees = new ArrayList<CustomFee>();
		for (final var grpcFee : op.getCustomFeesList()) {
			final var grpcCollectorId = grpcFee.getFeeCollectorAccountId();
			final var collectorId = Id.fromGrpcAccount(grpcCollectorId);
			final var collector = accountStore.loadAccountOrFailWith(collectorId, INVALID_CUSTOM_FEE_COLLECTOR);

			final var fee = CustomFee.fromGrpc(grpcFee, collector);
			/* The CustomFee factory fully validates a fractional fee; but since fixed
			and royalty fees may reference tokens other than the pending creation, they
			need additional validation. */
			if (representsFixedHtsFee(grpcFee)) {
				validateAndInitFixedFee(grpcFee, op.getTokenType(), provisionalId, fee, collector);
			} else if (grpcFee.hasRoyaltyFee()) {
				validateAndInitRoyaltyFee(grpcFee, op.getTokenType(), provisionalId, fee, collector);
			}
			customFees.add(fee);
		}
		return customFees;
	}

	@Override
	public Predicate<TransactionBody> applicability() {
		return TransactionBody::hasTokenCreation;
	}

	@Override
	public Function<TransactionBody, ResponseCodeEnum> semanticCheck() {
		return SEMANTIC_CHECK;
	}

	@Override
	public void reclaimCreatedIds() {
		ids.reclaimProvisionalIds();
	}

	@Override
	public void resetCreatedIds() {
		ids.resetProvisionalIds();
	}

	public ResponseCodeEnum validate(TransactionBody txnBody) {
		TokenCreateTransactionBody op = txnBody.getTokenCreation();

		if (TokenTypesMapper.grpcTokenTypeToModelType(
				op.getTokenType()) == TokenType.NON_FUNGIBLE_UNIQUE && !dynamicProperties.areNftsEnabled()) {
			return NOT_SUPPORTED;
		}

		var validity = validator.memoCheck(op.getMemo());
		if (validity != OK) {
			return validity;
		}

		validity = validator.tokenSymbolCheck(op.getSymbol());
		if (validity != OK) {
			return validity;
		}

		validity = validator.tokenNameCheck(op.getName());
		if (validity != OK) {
			return validity;
		}

		validity = typeCheck(op.getTokenType(), op.getInitialSupply(), op.getDecimals());
		if (validity != OK) {
			return validity;
		}

		validity = supplyTypeCheck(op.getSupplyType(), op.getMaxSupply());
		if (validity != OK) {
			return validity;
		}

		validity = suppliesCheck(op.getInitialSupply(), op.getMaxSupply());
		if (validity != OK) {
			return validity;
		}

		if (!op.hasTreasury()) {
			return INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
		}

		validity = checkKeys(
				op.hasAdminKey(), op.getAdminKey(),
				op.hasKycKey(), op.getKycKey(),
				op.hasWipeKey(), op.getWipeKey(),
				op.hasSupplyKey(), op.getSupplyKey(),
				op.hasFreezeKey(), op.getFreezeKey(),
				op.hasFeeScheduleKey(), op.getFeeScheduleKey());
		if (validity != OK) {
			return validity;
		}

		if (op.getFreezeDefault() && !op.hasFreezeKey()) {
			return TOKEN_HAS_NO_FREEZE_KEY;
		}
		return validateAutoRenewAccount(op);
	}

	private ResponseCodeEnum validateAutoRenewAccount(final TokenCreateTransactionBody op) {
		ResponseCodeEnum validity = OK;
		if (op.hasAutoRenewAccount()) {
			validity = validator.isValidAutoRenewPeriod(op.getAutoRenewPeriod()) ? OK : INVALID_RENEWAL_PERIOD;
			return validity;
		} else {
			if (op.getExpiry().getSeconds() <= txnCtx.consensusTime().getEpochSecond()) {
				return INVALID_EXPIRATION_TIME;
			}
		}
		return validity;
	}

	private boolean representsFixedHtsFee(com.hederahashgraph.api.proto.java.CustomFee fee) {
		return fee.hasFixedFee() && fee.getFixedFee().hasDenominatingTokenId();
	}

	private void validateAndInitRoyaltyFee(
			com.hederahashgraph.api.proto.java.CustomFee grpcFee,
			com.hederahashgraph.api.proto.java.TokenType tokenType,
			Id wildcardDenom,
			CustomFee fee,
			Account collector
	) {
		final var isUnique = tokenType == NON_FUNGIBLE_UNIQUE;
		validateTrue(isUnique, CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE);

		final var grpcRoyaltyFee = grpcFee.getRoyaltyFee();
		final var exchangeValueFraction = grpcRoyaltyFee.getExchangeValueFraction();
		final var isGreaterThanOne = exchangeValueFraction.getNumerator() > exchangeValueFraction.getDenominator();
		validateFalse(isGreaterThanOne, ROYALTY_FRACTION_CANNOT_EXCEED_ONE);

		final var fallbackGrpc = grpcRoyaltyFee.getFallbackFee();
		if (fallbackGrpc.hasDenominatingTokenId()) {
			Id feeDenom;
			final var denomTokenId = fallbackGrpc.getDenominatingTokenId();
			if (denomTokenId.getTokenNum() == 0) {
				feeDenom = wildcardDenom;
			} else {
				final var denomToken = typedTokenStore.loadTokenOrFailWith(
						Id.fromGrpcToken(denomTokenId),
						INVALID_TOKEN_ID_IN_CUSTOM_FEES);
				validateTrue(
						collector.getAssociatedTokens().contains(denomToken.getId()),
						TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR);
				feeDenom = Id.fromGrpcToken(denomTokenId);
			}
			fee.getRoyaltyFee().getFallbackFee().setDenominatingTokenId(feeDenom);
		} else {
			fee.getRoyaltyFee().getFallbackFee().setDenominatingTokenId(null);
		}
	}

	private void validateAndInitFixedFee(
			com.hederahashgraph.api.proto.java.CustomFee grpcFee,
			com.hederahashgraph.api.proto.java.TokenType tokenType,
			Id wildcardDenom,
			CustomFee fee,
			Account collector
	) {
		final var grpcDenomId = grpcFee.getFixedFee().getDenominatingTokenId();
		if (grpcDenomId.getTokenNum() != 0) {
			final var denom = typedTokenStore.loadTokenOrFailWith(
					Id.fromGrpcToken(grpcDenomId), INVALID_TOKEN_ID_IN_CUSTOM_FEES);
			final var isDenomFungible = denom.getType() == TokenType.FUNGIBLE_COMMON;
			validateTrue(isDenomFungible, CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON);
			final var isCollectorAssociated = collector.getAssociatedTokens().contains(denom.getId());
			validateTrue(isCollectorAssociated, TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR);
		} else {
			validateFalse(tokenType.equals(NON_FUNGIBLE_UNIQUE), CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON);
			fee.getFixedFee().setDenominatingTokenId(wildcardDenom);
		}
	}
}
