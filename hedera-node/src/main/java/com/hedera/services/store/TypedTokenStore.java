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

import com.hedera.services.exceptions.InvalidTransactionException;
import com.hedera.services.ledger.accounts.BackingTokenRels;
import com.hedera.services.records.TransactionRecordService;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.store.models.Account;
import com.hedera.services.store.models.Id;
import com.hedera.services.store.models.OwnershipTracker;
import com.hedera.services.store.models.Token;
import com.hedera.services.store.models.TokenRelationship;
import com.hedera.services.store.models.UniqueToken;
import com.hedera.services.store.tokens.views.UniqTokenViewsManager;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.EntityNumPair;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import com.swirlds.merkle.map.MerkleMap;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

import static com.hedera.services.exceptions.ValidationUtils.validateFalse;
import static com.hedera.services.exceptions.ValidationUtils.validateTrue;
import static com.hedera.services.state.submerkle.EntityId.MISSING_ENTITY_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

/**
 * Loads and saves token-related entities to and from the Swirlds state, hiding
 * the details of Merkle types from client code by providing an interface in
 * terms of model objects whose methods can perform validated business logic.
 * <p>
 * When loading an token, fails fast by throwing an {@link InvalidTransactionException}
 * if the token is not usable in normal business logic. There are three such
 * cases:
 * <ol>
 * <li>The token is missing.</li>
 * <li>The token is deleted.</li>
 * <li>The token is expired and pending removal.</li>
 * </ol>
 * Note that in the third case, there <i>is</i> one valid use of the token;
 * namely, in an update transaction whose only purpose is to manually renew
 * the expired token. Such update transactions must use a dedicated
 * expiry-extension service, which will be implemented before TokenUpdate.
 * <p>
 * When saving a token or token relationship, invites an injected
 * {@link TransactionRecordService} to inspect the entity for changes that
 * may need to be included in the record of the transaction.
 */
@Singleton
public class TypedTokenStore {
	private final AccountStore accountStore;
	private final UniqTokenViewsManager uniqTokenViewsManager;
	private final TransactionRecordService transactionRecordService;
	private final Supplier<MerkleMap<EntityNum, MerkleToken>> tokens;
	private final Supplier<MerkleMap<EntityNumPair, MerkleUniqueToken>> uniqueTokens;
	private final Supplier<MerkleMap<EntityNumPair, MerkleTokenRelStatus>> tokenRels;

	/* Only needed for interoperability with legacy HTS during refactor */
	private final BackingTokenRels backingTokenRels;
	private final LegacyTreasuryRemover delegate;
	private final LegacyTreasuryAdder addKnownTreasury;

	@Inject
	public TypedTokenStore(
			AccountStore accountStore,
			TransactionRecordService transactionRecordService,
			Supplier<MerkleMap<EntityNum, MerkleToken>> tokens,
			Supplier<MerkleMap<EntityNumPair, MerkleUniqueToken>> uniqueTokens,
			Supplier<MerkleMap<EntityNumPair, MerkleTokenRelStatus>> tokenRels,
			BackingTokenRels backingTokenRels,
			UniqTokenViewsManager uniqTokenViewsManager,
			LegacyTreasuryAdder legacyStoreDelegate,
			LegacyTreasuryRemover delegate
	) {
		this.tokens = tokens;
		this.uniqTokenViewsManager = uniqTokenViewsManager;
		this.tokenRels = tokenRels;
		this.uniqueTokens = uniqueTokens;
		this.accountStore = accountStore;
		this.transactionRecordService = transactionRecordService;
		this.delegate = delegate;
		this.backingTokenRels = backingTokenRels;
		this.addKnownTreasury = legacyStoreDelegate;
	}

	static Pair<AccountID, TokenID> legacyReprOf(TokenRelationship rel) {
		final var tokenId = rel.getToken().getId();
		final var accountId = rel.getAccount().getId();
		return Pair.of(accountId.asGrpcAccount(), tokenId.asGrpcToken());
	}

	/**
	 * Returns the number of NFTs currently in the ledger state. (That is, the
	 * number of entries in the {@code uniqueTokens} map---NFTs are not marked
	 * deleted but actually removed from state when they are burned.)
	 *
	 * @return the current number of NFTs in the system
	 */
	public long currentMintedNfts() {
		return uniqueTokens.get().size();
	}

	/**
	 * Returns a model of the requested token relationship, with operations that
	 * can be used to implement business logic in a transaction.
	 * <p>
	 * The arguments <i>should</i> be model objects that were returned by the
	 * {@link TypedTokenStore#loadToken(Id)} and {@link AccountStore#loadAccount(Id)}
	 * methods, respectively, since it will very rarely (or never) be correct
	 * to do business logic on a relationship whose token or account have not
	 * been validated as usable.
	 *
	 * <b>IMPORTANT:</b> Changes to the returned model are not automatically persisted
	 * to state! The altered model must be passed to {@link TypedTokenStore#persistTokenRelationships(List)}
	 * in order for its changes to be applied to the Swirlds state, and included in the
	 * {@link com.hedera.services.state.submerkle.ExpirableTxnRecord} for the active transaction.
	 *
	 * @param token
	 * 		the token in the relationship to load
	 * @param account
	 * 		the account in the relationship to load
	 * @return a usable model of the token-account relationship
	 * @throws InvalidTransactionException
	 * 		if the requested relationship does not exist
	 */
	public TokenRelationship loadTokenRelationship(Token token, Account account) {
		final var tokenNum = EntityNum.fromModel(token.getId());
		final var accountNum = EntityNum.fromModel(account.getId());
		final var key = EntityNumPair.fromLongs(accountNum.longValue(), tokenNum.longValue());
		final var merkleTokenRel = tokenRels.get().get(key);

		validateUsable(merkleTokenRel);

		final var tokenRelationship = new TokenRelationship(token, account);
		tokenRelationship.initBalance(merkleTokenRel.getBalance());
		tokenRelationship.setKycGranted(merkleTokenRel.isKycGranted());
		tokenRelationship.setFrozen(merkleTokenRel.isFrozen());
		tokenRelationship.setAutomaticAssociation(merkleTokenRel.isAutomaticAssociation());

		tokenRelationship.markAsPersisted();

		return tokenRelationship;
	}

	/**
	 * Persists the given token relationships to the Swirlds state, inviting the injected
	 * {@link TransactionRecordService} to update the {@link com.hedera.services.state.submerkle.ExpirableTxnRecord}
	 * of the active transaction with these changes.
	 *
	 * @param tokenRelationships
	 * 		the token relationships to save
	 */
	public void persistTokenRelationships(List<TokenRelationship> tokenRelationships) {
		final var currentTokenRels = tokenRels.get();

		for (var tokenRelationship : tokenRelationships) {
			final var key = EntityNumPair.fromModelRel(tokenRelationship);
			if (tokenRelationship.isDestroyed()) {
				currentTokenRels.remove(key);
				backingTokenRels.removeFromExistingRels(legacyReprOf(tokenRelationship));
			} else {
				persistNonDestroyed(tokenRelationship, key, currentTokenRels);
			}
		}
		transactionRecordService.includeChangesToTokenRels(tokenRelationships);
	}

	private void persistNonDestroyed(
			TokenRelationship modelRel,
			EntityNumPair key,
			MerkleMap<EntityNumPair, MerkleTokenRelStatus> currentTokenRels
	) {
		final var isNewRel = modelRel.isNotYetPersisted();
		final var mutableTokenRel = isNewRel
				? new MerkleTokenRelStatus()
				: currentTokenRels.getForModify(key);
		mutableTokenRel.setBalance(modelRel.getBalance());
		mutableTokenRel.setFrozen(modelRel.isFrozen());
		mutableTokenRel.setKycGranted(modelRel.isKycGranted());
		mutableTokenRel.setAutomaticAssociation(modelRel.isAutomaticAssociation());
		if (isNewRel) {
			currentTokenRels.put(key, mutableTokenRel);
			alertTokenBackingStoreOfNew(modelRel);
		}
	}

	/**
	 * Invites the injected {@link TransactionRecordService} to include the changes to the exported transaction record
	 * Currently, the only implemented tracker is the {@link OwnershipTracker} which records the changes to the
	 * ownership
	 * of {@link UniqueToken}
	 *
	 * @param ownershipTracker
	 * 		holds changes to {@link UniqueToken} ownership
	 */
	public void persistTrackers(OwnershipTracker ownershipTracker) {
		transactionRecordService.includeOwnershipChanges(ownershipTracker);
	}

	/**
	 * Returns a model of the requested token, with operations that can be used to
	 * implement business logic in a transaction.
	 *
	 * <b>IMPORTANT:</b> Changes to the returned model are not automatically persisted
	 * to state! The altered model must be passed to {@link TypedTokenStore#persistToken(Token)}
	 * in order for its changes to be applied to the Swirlds state, and included in the
	 * {@link com.hedera.services.state.submerkle.ExpirableTxnRecord} for the active transaction.
	 *
	 * @param id
	 * 		the token to load
	 * @return a usable model of the token
	 * @throws InvalidTransactionException
	 * 		if the requested token is missing, deleted, or expired and pending removal
	 */
	public Token loadToken(Id id) {
		final var merkleToken = tokens.get().get(EntityNum.fromModel(id));

		validateUsable(merkleToken);

		final var token = new Token(id);
		initModelAccounts(token, merkleToken.treasury(), merkleToken.autoRenewAccount());
		initModelFields(token, merkleToken);

		return token;
	}

	/**
	 * Returns a {@link UniqueToken} model of the requested unique token, with operations that can be used to
	 * implement business logic in a transaction.
	 *
	 * @param token
	 * 		the token model, on which to load the of the unique token
	 * @param serialNumbers
	 * 		the serial numbers to load
	 * @throws InvalidTransactionException
	 * 		if the requested token class is missing, deleted, or expired and pending removal
	 */
	public void loadUniqueTokens(Token token, List<Long> serialNumbers) {
		final var tokenNum = EntityNum.fromModel(token.getId());
		final var loadedUniqueTokens = new HashMap<Long, UniqueToken>();
		final var curUniqueTokens = uniqueTokens.get();
		for (long serialNumber : serialNumbers) {
			final var uniqueTokenKey = EntityNumPair.fromLongs(tokenNum.longValue(), serialNumber);
			final var merkleUniqueToken = curUniqueTokens.get(uniqueTokenKey);
			validateUsable(merkleUniqueToken);
			final var uniqueToken = new UniqueToken(token.getId(), serialNumber);
			initModelFields(uniqueToken, merkleUniqueToken);
			loadedUniqueTokens.put(serialNumber, uniqueToken);
		}
		token.setLoadedUniqueTokens(loadedUniqueTokens);
	}

	/**
	 * Use carefully. Returns a model of the requested token which may be marked as deleted or
	 * even marked as auto-removed if the Merkle state has no token with that id.
	 *
	 * @param id
	 * 		the token to load
	 * @return a usable model of the token
	 */
	public Token loadPossiblyDeletedOrAutoRemovedToken(Id id) {
		final var key = EntityNum.fromModel(id);
		final var merkleToken = tokens.get().get(key);

		final var token = new Token(id);
		if (merkleToken != null) {
			initModelAccounts(token, merkleToken.treasury(), merkleToken.autoRenewAccount());
			initModelFields(token, merkleToken);
		} else {
			token.markAutoRemoved();
		}

		return token;
	}

	/**
	 * Loads a token from the swirlds state. Throws the given response code upon unusable token.
	 *
	 * @param id
	 * 		- id of the token to be loaded
	 * @param code
	 * 		- the {@link ResponseCodeEnum} code to fail with if the token is not present or is erroneous
	 * @return - the loaded token
	 */
	public Token loadTokenOrFailWith(Id id, ResponseCodeEnum code) {
		final var key = EntityNum.fromModel(id);
		final var merkleToken = tokens.get().get(key);

		validateUsable(merkleToken, code);

		final var token = new Token(id);
		initModelAccounts(token, merkleToken.treasury(), merkleToken.autoRenewAccount());
		initModelFields(token, merkleToken);
		return token;
	}

	/**
	 * Persists the given token to the Swirlds state, inviting the injected {@link TransactionRecordService}
	 * to update the {@link com.hedera.services.state.submerkle.ExpirableTxnRecord} of the active transaction
	 * with these changes.
	 *
	 * @param token
	 * 		the token to save
	 */
	public void persistToken(Token token) {
		final var key = EntityNum.fromLong(token.getId().getNum());
		final var mutableToken = tokens.get().getForModify(key);
		mapModelChangesToMutable(token, mutableToken);

		final var treasury = mutableToken.treasury();
		if (token.hasMintedUniqueTokens()) {
			persistMinted(token.mintedUniqueTokens(), treasury);
		}
		if (token.hasRemovedUniqueTokens()) {
			destroyRemoved(token.removedUniqueTokens(), treasury);
		}

		/* Only needed during HTS refactor.
		Will be removed once all operations that refer to the knownTreasuries in-memory structure are refactored */
		if (token.isDeleted()) {
			final AccountID affectedTreasury = token.getTreasury().getId().asGrpcAccount();
			final TokenID mutatedToken = token.getId().asGrpcToken();
			delegate.removeKnownTreasuryForToken(affectedTreasury, mutatedToken);
		}
		transactionRecordService.includeChangesToToken(token);
	}

	/**
	 * Instantiates a new {@link MerkleToken} based on the given new mutable {@link Token}.
	 * Maps the properties between the mutable and immutable token, and later puts the immutable one in state.
	 * Adds the token's treasury to the known treasuries map.
	 *
	 * @param token
	 * 		- the model of the token to be persisted in state.
	 */
	public void persistNew(Token token) {
		/* create new merkle token */
		final var newMerkleTokenId = EntityNum.fromLong(token.getId().getNum());
		final var newMerkleToken = new MerkleToken(
				token.getExpiry(),
				token.getTotalSupply(),
				token.getDecimals(),
				token.getSymbol(),
				token.getName(),
				token.isFrozenByDefault(),
				!token.hasKycKey(),
				token.getTreasury().getId().asEntityId()
		);
		/* map changes */
		mapModelChangesToMutable(token, newMerkleToken);

		tokens.get().put(newMerkleTokenId, newMerkleToken);
		addKnownTreasury.perform(token.getTreasury().getId().asGrpcAccount(), token.getId().asGrpcToken());

		transactionRecordService.includeChangesToToken(token);
	}

	private void destroyRemoved(List<UniqueToken> nfts, EntityId treasury) {
		final var curNfts = uniqueTokens.get();
		for (var nft : nfts) {
			final var merkleNftId = EntityNumPair.fromLongs(nft.getTokenId().getNum(), nft.getSerialNumber());
			curNfts.remove(merkleNftId);
			if (treasury.matches(nft.getOwner())) {
				uniqTokenViewsManager.burnNotice(merkleNftId, treasury);
			} else {
				uniqTokenViewsManager.wipeNotice(merkleNftId, new EntityId(nft.getOwner()));
			}
		}
	}

	private void persistMinted(List<UniqueToken> nfts, EntityId treasury) {
		final var curNfts = uniqueTokens.get();
		for (var nft : nfts) {
			final var merkleNftId = EntityNumPair.fromLongs(nft.getTokenId().getNum(), nft.getSerialNumber());
			final var merkleNft = new MerkleUniqueToken(MISSING_ENTITY_ID, nft.getMetadata(), nft.getCreationTime());
			curNfts.put(merkleNftId, merkleNft);
			uniqTokenViewsManager.mintNotice(merkleNftId, treasury);
		}
	}

	private void validateUsable(MerkleTokenRelStatus merkleTokenRelStatus) {
		validateTrue(merkleTokenRelStatus != null, TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);
	}

	private void validateUsable(MerkleToken merkleToken) {
		validateTrue(merkleToken != null, INVALID_TOKEN_ID);
		validateFalse(merkleToken.isDeleted(), TOKEN_WAS_DELETED);
	}

	private void validateUsable(MerkleToken merkleToken, ResponseCodeEnum code) {
		validateTrue(merkleToken != null, code);
		validateFalse(merkleToken.isDeleted(), code);
	}

	private void validateUsable(MerkleUniqueToken merkleUniqueToken) {
		validateTrue(merkleUniqueToken != null, INVALID_NFT_ID);
	}

	private void mapModelChangesToMutable(Token token, MerkleToken mutableToken) {
		final var newAutoRenewAccount = token.getAutoRenewAccount();
		if (newAutoRenewAccount != null) {
			mutableToken.setAutoRenewAccount(new EntityId(newAutoRenewAccount.getId()));
			mutableToken.setAutoRenewPeriod(token.getAutoRenewPeriod());
		}
		mutableToken.setTreasury(new EntityId(token.getTreasury().getId()));
		mutableToken.setTotalSupply(token.getTotalSupply());
		mutableToken.setAccountsFrozenByDefault(token.isFrozenByDefault());
		mutableToken.setLastUsedSerialNumber(token.getLastUsedSerialNumber());

		mutableToken.setTokenType(token.getType());
		mutableToken.setSupplyType(token.getSupplyType());

		mutableToken.setMemo(token.getMemo());

		mutableToken.setAdminKey(token.getAdminKey());
		mutableToken.setSupplyKey(token.getSupplyKey());
		mutableToken.setWipeKey(token.getWipeKey());
		mutableToken.setFreezeKey(token.getFreezeKey());
		mutableToken.setKycKey(token.getKycKey());
		mutableToken.setFeeScheduleKey(token.getFeeScheduleKey());

		mutableToken.setMaxSupply(token.getMaxSupply());
		mutableToken.setDeleted(token.isDeleted());

		if (token.getCustomFees() != null) {
			mutableToken.setFeeSchedule(token.getCustomFees());
		}

		mutableToken.setExpiry(token.getExpiry());
	}

	private void initModelAccounts(Token token, EntityId _treasuryId, @Nullable EntityId _autoRenewId) {
		if (_autoRenewId != null) {
			final var autoRenewId = new Id(_autoRenewId.shard(), _autoRenewId.realm(), _autoRenewId.num());
			final var autoRenew = accountStore.loadAccount(autoRenewId);
			token.setAutoRenewAccount(autoRenew);
		}
		final var treasuryId = new Id(_treasuryId.shard(), _treasuryId.realm(), _treasuryId.num());
		final var treasury = accountStore.loadAccount(treasuryId);
		token.setTreasury(treasury);
	}

	private void initModelFields(Token token, MerkleToken immutableToken) {
		token.initTotalSupply(immutableToken.totalSupply());
		token.initSupplyConstraints(immutableToken.supplyType(), immutableToken.maxSupply());
		token.setKycKey(immutableToken.getKycKey());
		token.setFreezeKey(immutableToken.getFreezeKey());
		token.setSupplyKey(immutableToken.getSupplyKey());
		token.setWipeKey(immutableToken.getWipeKey());
		token.setFrozenByDefault(immutableToken.accountsAreFrozenByDefault());
		token.setAdminKey(immutableToken.getAdminKey());
		token.setFeeScheduleKey(immutableToken.getFeeScheduleKey());
		token.setType(immutableToken.tokenType());
		token.setLastUsedSerialNumber(immutableToken.getLastUsedSerialNumber());
		token.setIsDeleted(immutableToken.isDeleted());
		token.setExpiry(immutableToken.expiry());
		token.setMemo(immutableToken.memo());
		token.setAutoRenewPeriod(immutableToken.autoRenewPeriod());
	}

	private void initModelFields(UniqueToken uniqueToken, MerkleUniqueToken immutableUniqueToken) {
		uniqueToken.setCreationTime(immutableUniqueToken.getCreationTime());
		uniqueToken.setMetadata(immutableUniqueToken.getMetadata());
		uniqueToken.setOwner(immutableUniqueToken.getOwner().asId());
	}

	private void alertTokenBackingStoreOfNew(TokenRelationship newRel) {
		backingTokenRels.addToExistingRels(legacyReprOf(newRel));
	}

	@FunctionalInterface
	public interface LegacyTreasuryAdder {
		void perform(final AccountID aId, final TokenID tId);
	}

	@FunctionalInterface
	public interface LegacyTreasuryRemover {
		void removeKnownTreasuryForToken(final AccountID aId, final TokenID tId);
	}
}
