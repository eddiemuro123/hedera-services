package com.hedera.services.context.init;

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

import com.hedera.services.ledger.accounts.BackingStore;
import com.hedera.services.state.StateAccessor;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.store.models.NftId;
import com.hedera.services.store.schedule.ScheduleStore;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.services.store.tokens.views.UniqTokenViewsManager;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.EntityNumPair;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.swirlds.merkle.map.MerkleMap;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;


@ExtendWith(MockitoExtension.class)
class StoreInitializationFlowTest {
	@Mock
	private TokenStore tokenStore;
	@Mock
	private ScheduleStore scheduleStore;
	@Mock
	private StateAccessor stateAccessor;
	@Mock
	private UniqTokenViewsManager uniqTokenViewsManager;
	@Mock
	private BackingStore<AccountID, MerkleAccount> backingAccounts;
	@Mock
	private BackingStore<NftId, MerkleUniqueToken> backingNfts;
	@Mock
	private BackingStore<Pair<AccountID, TokenID>, MerkleTokenRelStatus> backingTokenRels;
	@Mock
	private MerkleMap<EntityNum, MerkleToken> tokens;
	@Mock
	private MerkleMap<EntityNumPair, MerkleUniqueToken> nfts;

	private StoreInitializationFlow subject;

	@BeforeEach
	void setUp() {
		subject = new StoreInitializationFlow(
				tokenStore,
				scheduleStore,
				stateAccessor,
				uniqTokenViewsManager,
				backingAccounts,
				backingNfts,
				backingTokenRels);
	}

	@Test
	void initsAsExpected() {
		given(stateAccessor.tokens()).willReturn(tokens);
		given(stateAccessor.uniqueTokens()).willReturn(nfts);

		// when:
		subject.run();

		// then:
		verify(backingTokenRels).rebuildFromSources();
		verify(backingAccounts).rebuildFromSources();
		verify(backingNfts).rebuildFromSources();
		verify(tokenStore).rebuildViews();
		verify(scheduleStore).rebuildViews();
		verify(uniqTokenViewsManager).rebuildNotice(tokens, nfts);
	}
}
