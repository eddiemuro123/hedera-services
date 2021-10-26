package com.hedera.services.store;

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

import com.hedera.services.context.annotations.CompositeProps;
import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.ledger.TransactionalLedger;
import com.hedera.services.ledger.accounts.BackingNfts;
import com.hedera.services.ledger.accounts.BackingStore;
import com.hedera.services.ledger.accounts.BackingTokenRels;
import com.hedera.services.ledger.properties.ChangeSummaryManager;
import com.hedera.services.ledger.properties.NftProperty;
import com.hedera.services.ledger.properties.TokenRelProperty;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.store.contracts.CodeCache;
import com.hedera.services.store.models.NftId;
import com.hedera.services.store.schedule.HederaScheduleStore;
import com.hedera.services.store.schedule.ScheduleStore;
import com.hedera.services.store.tokens.HederaTokenStore;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.services.store.tokens.annotations.AreFcotmrQueriesDisabled;
import com.hedera.services.store.tokens.annotations.AreTreasuryWildcardsEnabled;
import com.hedera.services.store.tokens.views.ConfigDrivenUniqTokenViewFactory;
import com.hedera.services.store.tokens.views.UniqTokenViewFactory;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import org.apache.commons.lang3.tuple.Pair;
import org.ethereum.db.ServicesRepositoryRoot;

import javax.inject.Singleton;

@Module
public abstract class StoresModule {
	@Binds
	@Singleton
	public abstract UniqTokenViewFactory bindTokenViewFactory(ConfigDrivenUniqTokenViewFactory configDrivenFactory);

	@Binds
	@Singleton
	public abstract TokenStore bindTokenStore(HederaTokenStore hederaTokenStore);

	@Binds
	@Singleton
	public abstract BackingStore<NftId, MerkleUniqueToken> bindBackingNfts(BackingNfts backingNfts);

	@Binds
	@Singleton
	public abstract BackingStore<Pair<AccountID, TokenID>, MerkleTokenRelStatus> bindBackingTokenRels(
			BackingTokenRels backingTokenRels);

	@Binds
	@Singleton
	public abstract ScheduleStore bindScheduleStore(HederaScheduleStore scheduleStore);

	@Provides
	@Singleton
	public static TransactionalLedger<NftId, NftProperty, MerkleUniqueToken> provideNftsLedger(
			BackingStore<NftId, MerkleUniqueToken> backingNfts
	) {
		return new TransactionalLedger<>(
				NftProperty.class,
				MerkleUniqueToken::new,
				backingNfts,
				new ChangeSummaryManager<>());
	}

	@Provides
	@Singleton
	public static TransactionalLedger<Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus> provideTokenRelsLedger(
			BackingStore<Pair<AccountID, TokenID>, MerkleTokenRelStatus> backingTokenRels
	) {
		final var tokenRelsLedger = new TransactionalLedger<>(
				TokenRelProperty.class,
				MerkleTokenRelStatus::new,
				backingTokenRels,
				new ChangeSummaryManager<>());
		tokenRelsLedger.setKeyToString(BackingTokenRels::readableTokenRel);
		return tokenRelsLedger;
	}

	@Provides
	@AreFcotmrQueriesDisabled
	public static boolean provideAreFcotmrQueriesDisabled(@CompositeProps PropertySource properties) {
		return !properties.getBooleanProperty("tokens.nfts.areQueriesEnabled");
	}

	@Provides
	@AreTreasuryWildcardsEnabled
	public static boolean provideAreTreasuryWildcardsEnabled(@CompositeProps PropertySource properties) {
		return properties.getBooleanProperty("tokens.nfts.useTreasuryWildcards");
	}

	@Provides
	@Singleton
	public static CodeCache provideCodeCache(NodeLocalProperties properties, ServicesRepositoryRoot repositoryRoot) {
		return new CodeCache(properties, repositoryRoot);
	}
}
