package com.hedera.services.sigs;

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

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.files.HederaFs;
import com.hedera.services.keys.HederaKeyActivation;
import com.hedera.services.keys.OnlyIfSigVerifiableValid;
import com.hedera.services.ledger.accounts.BackingStore;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.sigs.annotations.HandleSigReqs;
import com.hedera.services.sigs.annotations.PayerSigReqs;
import com.hedera.services.sigs.annotations.RetryingSigReqs;
import com.hedera.services.sigs.order.PolicyBasedSigWaivers;
import com.hedera.services.sigs.order.SigRequirements;
import com.hedera.services.sigs.order.SignatureWaivers;
import com.hedera.services.sigs.utils.PrecheckUtils;
import com.hedera.services.sigs.verification.SyncVerifier;
import com.hedera.services.state.logic.PayerSigValidity;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleTopic;
import com.hedera.services.stats.MiscRunningAvgs;
import com.hedera.services.stats.MiscSpeedometers;
import com.hedera.services.store.schedule.ScheduleStore;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.services.store.tokens.views.internals.PermHashInteger;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.swirlds.common.Platform;
import com.swirlds.common.crypto.TransactionSignature;
import com.swirlds.merkle.map.MerkleMap;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hedera.services.sigs.metadata.DelegatingSigMetadataLookup.backedLookupsFor;
import static com.hedera.services.sigs.metadata.DelegatingSigMetadataLookup.defaultAccountRetryingLookupsFor;
import static com.hedera.services.sigs.metadata.DelegatingSigMetadataLookup.defaultLookupsFor;
import static com.hedera.services.sigs.metadata.SigMetadataLookup.REF_LOOKUP_FACTORY;
import static com.hedera.services.sigs.metadata.SigMetadataLookup.SCHEDULE_REF_LOOKUP_FACTORY;
import static com.hedera.services.state.logic.TerminalSigStatuses.TERMINAL_SIG_STATUSES;

@Module
public abstract class SigsModule {
	@Binds
	@Singleton
	public abstract SignatureWaivers provideSignatureWaivers(PolicyBasedSigWaivers policyBasedSigWaivers);

	@Provides
	@Singleton
	public static SyncVerifier provideSyncVerifier(Platform platform) {
		return platform.getCryptography()::verifySync;
	}

	@Provides
	@Singleton
	public static BiPredicate<JKey, TransactionSignature> provideValidityTest(SyncVerifier syncVerifier) {
		return new OnlyIfSigVerifiableValid(syncVerifier);
	}

	@Provides
	@Singleton
	@HandleSigReqs
	public static SigRequirements provideHandleSigReqs(
			HederaFs hfs,
			TokenStore tokenStore,
			ScheduleStore scheduleStore,
			SignatureWaivers signatureWaivers,
			GlobalDynamicProperties dynamicProperties,
			BackingStore<AccountID, MerkleAccount> backingAccounts,
			Supplier<MerkleMap<PermHashInteger, MerkleTopic>> topics,
			Supplier<MerkleMap<PermHashInteger, MerkleAccount>> accounts
	) {
		final var sigMetaLookup = backedLookupsFor(
				hfs,
				backingAccounts,
				topics,
				accounts,
				REF_LOOKUP_FACTORY.apply(tokenStore),
				SCHEDULE_REF_LOOKUP_FACTORY.apply(scheduleStore));
		return new SigRequirements(sigMetaLookup, dynamicProperties, signatureWaivers);
	}

	@Provides
	@Singleton
	@RetryingSigReqs
	public static SigRequirements provideQuerySigReqs(
			HederaFs hfs,
			TokenStore tokenStore,
			ScheduleStore scheduleStore,
			SignatureWaivers signatureWaivers,
			MiscRunningAvgs runningAvgs,
			MiscSpeedometers speedometers,
			NodeLocalProperties nodeLocalProperties,
			GlobalDynamicProperties dynamicProperties,
			Supplier<MerkleMap<PermHashInteger, MerkleTopic>> topics,
			Supplier<MerkleMap<PermHashInteger, MerkleAccount>> accounts
	) {
		final var sigMetaLookup = defaultAccountRetryingLookupsFor(
				hfs,
				nodeLocalProperties,
				accounts,
				topics,
				REF_LOOKUP_FACTORY.apply(tokenStore),
				SCHEDULE_REF_LOOKUP_FACTORY.apply(scheduleStore),
				runningAvgs,
				speedometers);
		return new SigRequirements(sigMetaLookup, dynamicProperties, signatureWaivers);
	}

	@Provides
	@Singleton
	@PayerSigReqs
	public static SigRequirements providePayerSigReqs(
			HederaFs hfs,
			TokenStore tokenStore,
			ScheduleStore scheduleStore,
			SignatureWaivers signatureWaivers,
			GlobalDynamicProperties dynamicProperties,
			Supplier<MerkleMap<PermHashInteger, MerkleTopic>> topics,
			Supplier<MerkleMap<PermHashInteger, MerkleAccount>> accounts
	) {
		final var sigMetaLookup = defaultLookupsFor(
				hfs,
				accounts,
				topics,
				REF_LOOKUP_FACTORY.apply(tokenStore),
				SCHEDULE_REF_LOOKUP_FACTORY.apply(scheduleStore));
		return new SigRequirements(sigMetaLookup, dynamicProperties, signatureWaivers);
	}

	@Provides
	@Singleton
	public static Predicate<TransactionBody> provideQueryPaymentTest(AccountID nodeAccount) {
		return PrecheckUtils.queryPaymentTestFor(nodeAccount);
	}

	@Provides
	@Singleton
	public static Predicate<ResponseCodeEnum> provideTerminalSigStatusTest() {
		return TERMINAL_SIG_STATUSES;
	}

	@Provides
	@Singleton
	public static PayerSigValidity providePayerSigValidity() {
		return HederaKeyActivation::payerSigIsActive;
	}

	@Provides
	@Singleton
	public static ExpansionHelper provideExpansionHelper() {
		return HederaToPlatformSigOps::expandIn;
	}
}
