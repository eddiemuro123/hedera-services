package com.hedera.services.store.tokens.views;

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

import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.merkle.MerkleUniqueTokenId;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.services.store.tokens.views.utils.MultiSourceRange;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenNftInfo;
import com.swirlds.fchashmap.FCOneToManyRelation;
import com.swirlds.fcmap.FCMap;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.function.Supplier;

/**
 * A {@link UniqTokenView} that answers requests for an account's unique tokens using
 * both a {@code nftsByOwner} {@link FCOneToManyRelation} and a {@code treasuryNftsByType}
 * {@link FCOneToManyRelation}.
 *
 * That is, this class assumes an account's owned unique tokens have <i>two distinct sources</i>:
 * <ol>
 * 	<li>Ownership from being a {@link com.hederahashgraph.api.proto.java.NftTransfer} receiver in a {@code
 * 	CryptoTransfer}.</li>
 * 	<li>Ownership from being the treasury for one or more non-fungible unique token types.</li>
 * </ol>
 *
 * When an instance receives a request for a sub-list of an account's owned tokens, it begins
 * with the non-treasury source; and then continues with treasury sources until none exist,
 * or the sub-list is fully constructed. (This process uses a {@link MultiSourceRange} instance
 * to manage the logic of querying multiple sources.)
 */
public class TreasuryWildcardsUniqTokenView extends AbstractUniqTokenView {
	private final TokenStore tokenStore;
	private final Supplier<FCOneToManyRelation<Integer, Long>> nftsByOwner;
	private final Supplier<FCOneToManyRelation<Integer, Long>> treasuryNftsByType;

	public TreasuryWildcardsUniqTokenView(
			TokenStore tokenStore,
			Supplier<FCMap<MerkleEntityId, MerkleToken>> tokens,
			Supplier<FCMap<MerkleUniqueTokenId, MerkleUniqueToken>> nfts,
			Supplier<FCOneToManyRelation<Integer, Long>> nftsByType,
			Supplier<FCOneToManyRelation<Integer, Long>> nftsByOwner,
			Supplier<FCOneToManyRelation<Integer, Long>> treasuryNftsByType
	) {
		super(tokens, nfts, nftsByType);

		this.nftsByOwner = nftsByOwner;
		this.treasuryNftsByType = treasuryNftsByType;

		this.tokenStore = tokenStore;
	}

	@Override
	public List<TokenNftInfo> ownedAssociations(@Nonnull AccountID owner, long start, long end) {
		final var accountId = EntityId.fromGrpcAccountId(owner);
		final var curNftsByOwner = nftsByOwner.get();
		final var numOwnedViaTransfer = curNftsByOwner.getCount(accountId.identityCode());
		final var multiSourceRange = new MultiSourceRange((int) start, (int) end, numOwnedViaTransfer);

		final var range = multiSourceRange.rangeForCurrentSource();
		final var answer = accumulatedInfo(nftsByOwner.get(), accountId, range.getLeft(), range.getRight(), null, owner);
		if (!multiSourceRange.isRequestedRangeExhausted()) {
			tryToCompleteWithTreasuryOwned(owner, multiSourceRange, answer);
		}
		return answer;
	}

	private void tryToCompleteWithTreasuryOwned(
			AccountID owner,
			MultiSourceRange multiSourceRange,
			List<TokenNftInfo> answer
	) {
		final var curTreasuryNftsByType = treasuryNftsByType.get();
		final var allServed = tokenStore.listOfTokensServed(owner);
		for (var served : allServed) {
			final var tokenId = EntityId.fromGrpcTokenId(served);
			multiSourceRange.moveToNewSource(curTreasuryNftsByType.getCount(tokenId.identityCode()));
			final var range = multiSourceRange.rangeForCurrentSource();
			final var infoHere = accumulatedInfo(curTreasuryNftsByType, tokenId, range.getLeft(), range.getRight(), served, owner);
			answer.addAll(infoHere);
			if (multiSourceRange.isRequestedRangeExhausted()) {
				break;
			}
		}
	}
}
