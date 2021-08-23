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

import com.hedera.services.fees.annotations.FunctionKey;
import com.hedera.services.store.TypedTokenStore;
import com.hedera.services.store.tokens.HederaTokenStore;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.services.txns.TransitionLogic;
import com.hedera.services.txns.token.process.Dissociation;
import com.hedera.services.txns.token.process.DissociationFactory;
import com.hederahashgraph.api.proto.java.TokenUpdateTransactionBody;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;

import javax.inject.Singleton;
import java.util.List;
import java.util.function.Predicate;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAccountWipe;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAssociateToAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenBurn;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenDissociateFromAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenFeeScheduleUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenFreezeAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenGrantKycToAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenMint;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenRevokeKycFromAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenUnfreezeAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenUpdate;

@Module
public abstract class TokenLogicModule {
	@Provides
	@IntoMap
	@FunctionKey(TokenCreate)
	public static List<TransitionLogic> provideTokenCreateLogic(TokenCreateTransitionLogic tokenCreateLogic) {
		return List.of(tokenCreateLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(TokenUpdate)
	public static List<TransitionLogic> provideTokenUpdateLogic(TokenUpdateTransitionLogic tokenUpdateLogic) {
		return List.of(tokenUpdateLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(TokenFeeScheduleUpdate)
	public static List<TransitionLogic> provideFeesUpdateLogic(TokenFeeScheduleUpdateTransitionLogic feesUpdateLogic) {
		return List.of(feesUpdateLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(TokenFreezeAccount)
	public static List<TransitionLogic> provideTokenFreezeLogic(TokenFreezeTransitionLogic tokenFreezeLogic) {
		return List.of(tokenFreezeLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(TokenUnfreezeAccount)
	public static List<TransitionLogic> provideTokenUnfreezeLogic(TokenUnfreezeTransitionLogic tokenUnfreezeLogic) {
		return List.of(tokenUnfreezeLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(TokenGrantKycToAccount)
	public static List<TransitionLogic> provideTokenGrantLogic(TokenGrantKycTransitionLogic tokenGrantLogic) {
		return List.of(tokenGrantLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(TokenRevokeKycFromAccount)
	public static List<TransitionLogic> provideTokenRevokeLogic(TokenRevokeKycTransitionLogic tokenRevokeLogic) {
		return List.of(tokenRevokeLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(TokenDelete)
	public static List<TransitionLogic> provideTokenDeleteLogic(TokenDeleteTransitionLogic tokenDeleteLogic) {
		return List.of(tokenDeleteLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(TokenMint)
	public static List<TransitionLogic> provideTokenMintLogic(TokenMintTransitionLogic tokenMintLogic) {
		return List.of(tokenMintLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(TokenBurn)
	public static List<TransitionLogic> provideTokenBurnLogic(TokenBurnTransitionLogic tokenBurnLogic) {
		return List.of(tokenBurnLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(TokenAccountWipe)
	public static List<TransitionLogic> provideTokenWipeLogic(TokenWipeTransitionLogic tokenWipeLogic) {
		return List.of(tokenWipeLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(TokenAssociateToAccount)
	public static List<TransitionLogic> provideTokenAssocLogic(TokenAssociateTransitionLogic tokenAssocLogic) {
		return List.of(tokenAssocLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(TokenDissociateFromAccount)
	public static List<TransitionLogic> provideTokenDissocLogic(TokenDissociateTransitionLogic tokenDissocLogic) {
		return List.of(tokenDissocLogic);
	}

	@Provides
	@Singleton
	public static Predicate<TokenUpdateTransactionBody> provideAffectsExpiryOnly() {
		return HederaTokenStore::affectsExpiryAtMost;
	}

	@Provides
	@Singleton
	public static TypedTokenStore.LegacyTreasuryRemover provideLegacyTreasuryRemover(TokenStore tokenStore) {
		return tokenStore::removeKnownTreasuryForToken;
	}

	@Provides
	@Singleton
	public static TypedTokenStore.LegacyTreasuryAdder provideLegacyTreasuryAdder(TokenStore tokenStore) {
		return tokenStore::addKnownTreasury;
	}

	@Provides
	@Singleton
	public static DissociationFactory provideDissociationFactory() {
		return Dissociation::loadFrom;
	}
}
