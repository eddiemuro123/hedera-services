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
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.records.TransactionRecordService;
import com.hedera.services.state.merkle.MerkleEntityAssociation;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.store.models.Account;
import com.hedera.services.store.models.Id;
import com.hedera.services.store.models.Token;
import com.hedera.services.store.models.TokenRelationship;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenCreateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenID;
import com.swirlds.fcmap.FCMap;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.hedera.services.exceptions.ValidationUtils.validateFalse;
import static com.hedera.services.exceptions.ValidationUtils.validateTrue;
import static com.hedera.services.state.submerkle.EntityId.fromGrpcAccountId;
import static com.hedera.services.utils.MiscUtils.asUsableFcKey;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;

/**
 * Loads and saves token-related entities to and from the Swirlds state, hiding
 * the details of Merkle types from client code by providing an interface in
 * terms of model objects whose methods can perform validated business logic.
 *
 * When loading an token, fails fast by throwing an {@link InvalidTransactionException}
 * if the token is not usable in normal business logic. There are three such
 * cases:
 * <ol>
 *     <li>The token is missing.</li>
 *     <li>The token is deleted.</li>
 *     <li>The token is expired and pending removal.</li>
 * </ol>
 * Note that in the third case, there <i>is</i> one valid use of the token;
 * namely, in an update transaction whose only purpose is to manually renew
 * the expired token. Such update transactions must use a dedicated
 * expiry-extension service, which will be implemented before TokenUpdate.
 *
 * When saving a token or token relationship, invites an injected
 * {@link TransactionRecordService} to inspect the entity for changes that
 * may need to be included in the record of the transaction.
 */
public class TypedTokenStore {
	private final AccountStore accountStore;
	private final TransactionRecordService transactionRecordService;
	private final Supplier<FCMap<MerkleEntityId, MerkleToken>> tokens;
	private final Supplier<FCMap<MerkleEntityAssociation, MerkleTokenRelStatus>> tokenRels;
	/* Only needed for interoperability with legacy HTS during refactor */
	private final BackingTokenRels backingTokenRels;

	public TypedTokenStore(
			AccountStore accountStore,
			TransactionRecordService transactionRecordService,
			Supplier<FCMap<MerkleEntityId, MerkleToken>> tokens,
			Supplier<FCMap<MerkleEntityAssociation, MerkleTokenRelStatus>> tokenRels,
			BackingTokenRels backingTokenRels
	) {
		this.tokens = tokens;
		this.tokenRels = tokenRels;
		this.accountStore = accountStore;
		this.transactionRecordService = transactionRecordService;

		this.backingTokenRels = backingTokenRels;
	}

	/**
	 * Returns a model of the requested token relationship, with operations that
	 * can be used to implement business logic in a transaction.
	 *
	 * The arguments <i>should</i> be model objects that were returned by the
	 * {@link TypedTokenStore#loadToken(Id)} and {@link AccountStore#loadAccount(Id)}
	 * methods, respectively, since it will very rarely (or never) be correct
	 * to do business logic on a relationship whose token or account have not
	 * been validated as usable.
	 *
	 * <b>IMPORTANT:</b> Changes to the returned model are not automatically persisted
	 * to state! The altered model must be passed to {@link TypedTokenStore#persistTokenRelationship(TokenRelationship)}
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
		final var tokenId = token.getId();
		final var accountId = account.getId();
		final var key = new MerkleEntityAssociation(
				accountId.getShard(), accountId.getRealm(), accountId.getNum(),
				tokenId.getShard(), tokenId.getRealm(), tokenId.getNum());
		final var merkleTokenRel = tokenRels.get().get(key);

		validateUsable(merkleTokenRel);

		final var tokenRelationship = new TokenRelationship(token, account);
		tokenRelationship.initBalance(merkleTokenRel.getBalance());
		tokenRelationship.setKycGranted(merkleTokenRel.isKycGranted());
		tokenRelationship.setFrozen(merkleTokenRel.isFrozen());

		tokenRelationship.setNotYetPersisted(false);

		return tokenRelationship;
	}

	/**
	 * Persists the given token relationship to the Swirlds state, inviting the injected
	 * {@link TransactionRecordService} to update the {@link com.hedera.services.state.submerkle.ExpirableTxnRecord}
	 * of the active transaction with these changes.
	 *
	 * @param tokenRelationship
	 * 		the token relationship to save
	 */
	public void persistTokenRelationship(TokenRelationship tokenRelationship) {
		final var tokenId = tokenRelationship.getToken().getId();
		final var accountId = tokenRelationship.getAccount().getId();
		final var key = new MerkleEntityAssociation(
				accountId.getShard(), accountId.getRealm(), accountId.getNum(),
				tokenId.getShard(), tokenId.getRealm(), tokenId.getNum());
		final var currentTokenRels = tokenRels.get();

		final var isNewRel = tokenRelationship.isNotYetPersisted();
		final var mutableTokenRel = isNewRel ? new MerkleTokenRelStatus() : currentTokenRels.getForModify(key);
		mutableTokenRel.setBalance(tokenRelationship.getBalance());
		mutableTokenRel.setFrozen(tokenRelationship.isFrozen());
		mutableTokenRel.setKycGranted(tokenRelationship.isKycGranted());

		if (isNewRel) {
			currentTokenRels.put(key, mutableTokenRel);
			/* Only done for interoperability with legacy HTS code during refactor */
			alertTokenBackingStoreOfNew(tokenRelationship);
		}

		transactionRecordService.includeChangesToTokenRel(tokenRelationship);
	}

	/**
	 * Removes the given token relationship to the Swirlds state
	 *
	 * @param tokenId
	 * @param accountId
	 */
	public void removeTokenRelationship(final Id tokenId, final Id accountId) {
		final var key = new MerkleEntityAssociation(
				accountId.getShard(), accountId.getRealm(), accountId.getNum(),
				tokenId.getShard(), tokenId.getRealm(), tokenId.getNum());
		tokenRels.get().remove(key);
	}

	/**
	 * Validates the Relationship before dissociation
	 * @param tokenId
	 * @param accountId
	 */
	public void validateRelationShip(final Id tokenId, final Id accountId) {
		final var key = new MerkleEntityAssociation(
				accountId.getShard(), accountId.getRealm(), accountId.getNum(),
				tokenId.getShard(), tokenId.getRealm(), tokenId.getNum());
		final var currentTokenRels = tokenRels.get();
		final var mutableTokenRel = currentTokenRels.getForModify(key);

		final var merkleEntityId = new MerkleEntityId(tokenId.getShard(), tokenId.getRealm(), tokenId.getNum());
		var merkleToken = tokens.get().get(merkleEntityId);

		validateTrue(merkleToken != null, INVALID_TOKEN_ID);
		validateTrue(mutableTokenRel != null, TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);

		final var treasury = merkleToken.treasury();
		final var treasuryId = new Id(treasury.shard(), treasury.realm(),treasury.num());

		validateFalse(treasuryId.equals(accountId), ACCOUNT_IS_TREASURY);
		validateFalse(!merkleToken.isDeleted() && mutableTokenRel.isFrozen(), ACCOUNT_FROZEN_FOR_TOKEN);

		var balance = mutableTokenRel.getBalance();
		if(balance > 0) {
			var expiry = Timestamp.newBuilder().setSeconds(merkleToken.expiry()).build();
			var isTokenExpired = !accountStore.getValidator().isValidExpiry(expiry);
			validateFalse(!merkleToken.isDeleted() && !isTokenExpired, TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES);
			 // transfer balance to treasury
			final var treasuryAccount = accountStore.loadAccount(treasuryId);
			final var token = loadToken(tokenId);
			final var account = accountStore.loadAccount(accountId);
			adjustTokenBalance(treasuryAccount, token, balance);
			adjustTokenBalance(account, token, -balance);
		}
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
		final var merkleToken = getMerkleToken(id);
		validateTrue(merkleToken != null, INVALID_TOKEN_ID);

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
		final var id = token.getId();
		final var key = new MerkleEntityId(id.getShard(), id.getRealm(), id.getNum());
		final var currentTokens = tokens.get();

		final var mutableToken = currentTokens.getForModify(key);
		mapModelChangesToMutable(token, mutableToken);

		transactionRecordService.includeChangesToToken(token);
	}

	/**
	 *
	 * @param token
	 * @param merkleToken the merkleToken to add to the token map of the Swirlds State
	 */
	/**
	 * Add the newly created token to the token map of the Swirlds State and persist the Token.
	 * @param token the token to add
	 * @param op The transactionBody of the TokenCreate request
	 * @param now epoc seconds of the consensus time of TokenCreate transaction.
	 */
	public void addToken(Token token, TokenCreateTransactionBody op, long now) {
		final var tokenId = token.getId();
		final var merkleEntityId = new MerkleEntityId(tokenId.getShard(), tokenId.getRealm(), tokenId.getNum());
		var merkleToken = createMerkleToken(op, token.getTreasury(), now);
		tokens.get().put(merkleEntityId, merkleToken);
		initModelAccounts(token, merkleToken.treasury(), merkleToken.autoRenewAccount());
		initModelFields(token, merkleToken);
		persistToken(token);
	}

	/**
	 * Freeze the token for the given account
	 * @param accountID
	 * @param tokenID
	 */
	public void freeze(AccountID accountID, TokenID tokenID) {
		var Ids = getAccountAndTokenIds(accountID, tokenID);
		setIsFrozen(Ids[0], Ids[1], true);
	}

	/**
	 * Unfreeze the token for the given account
	 * @param accountID
	 * @param tokenID
	 */
	public void unFreeze(AccountID accountID, TokenID tokenID) {
		var Ids = getAccountAndTokenIds(accountID, tokenID);
		setIsFrozen(Ids[0], Ids[1], false);
	}

	/**
	 * Grant Kyc for the pair account <--> token
	 * @param accountID
	 * @param tokenID
	 */
	public void grantKyc(AccountID accountID, TokenID tokenID) {
		var Ids = getAccountAndTokenIds(accountID, tokenID);
		setHasKyc(Ids[0], Ids[1], true);
	}

	/**
	 * Revoke Kyc for the pair account <--> token
	 * @param accountID
	 * @param tokenID
	 */
	public void revokeKyc(AccountID accountID, TokenID tokenID) {
		var Ids = getAccountAndTokenIds(accountID, tokenID);
		setHasKyc(Ids[0], Ids[1], false);
	}

	public void adjustTokenBalance(Account account, Token token, long adjustment) {
		final var tokenId = token.getId();
		final var accountId = account.getId();
		final var merkleEntityId = new MerkleEntityId(tokenId.getShard(), tokenId.getRealm(), tokenId.getNum());
		var merkleToken = tokens.get().get(merkleEntityId);

		validateTrue(merkleToken != null, INVALID_TOKEN_ID);

		accountStore.loadAccount(accountId);
		var merkleTokenRelStatus =  getMerkleTokenRelStatus(accountId, tokenId);

		validateFalse(!merkleToken.isDeleted() && merkleTokenRelStatus.isFrozen(), ACCOUNT_FROZEN_FOR_TOKEN);
		validateTrue(merkleTokenRelStatus.isKycGranted(), ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN);

		long balance = merkleTokenRelStatus.getBalance();
		long newBalance = balance + adjustment;
		validateTrue(newBalance >= 0, INSUFFICIENT_TOKEN_BALANCE);
		merkleTokenRelStatus.setBalance(newBalance);
	}

	private Id[] getAccountAndTokenIds(AccountID accountID, TokenID tokenID) {
		var token  = loadToken(new Id(tokenID.getShardNum(), tokenID.getRealmNum(), tokenID.getTokenNum()));
		var account = accountStore.loadAccount(new Id(accountID.getShardNum(), accountID.getRealmNum(), accountID.getAccountNum()));
		return new Id[]{account.getId(), token.getId()};
	}

	private void setHasKyc(final Id accountId, final Id tokenId, final boolean value) {
		validateUsable(tokenId, TOKEN_HAS_NO_KYC_KEY,	MerkleToken::kycKey);
		var merkleTokenRelStatus =  getMerkleTokenRelStatus(accountId, tokenId);
		merkleTokenRelStatus.setKycGranted(value);
	}

	private void setIsFrozen(final Id accountId, final Id tokenId,final boolean value) {
		validateUsable(tokenId, TOKEN_HAS_NO_FREEZE_KEY,	MerkleToken::freezeKey);
		var merkleTokenRelStatus =  getMerkleTokenRelStatus(accountId, tokenId);
		merkleTokenRelStatus.setFrozen(value);
	}

	private MerkleTokenRelStatus getMerkleTokenRelStatus(final Id accountId, final Id tokenId) {
		var relationship = Pair.of(
				AccountID.newBuilder()
						.setShardNum(accountId.getShard())
						.setRealmNum(accountId.getRealm())
						.setAccountNum(accountId.getNum())
						.build(),
				TokenID.newBuilder()
						.setShardNum(tokenId.getShard())
						.setRealmNum(tokenId.getRealm())
						.setTokenNum(tokenId.getNum())
						.build());

		return backingTokenRels.getRef(relationship);
	}

	private MerkleToken getMerkleToken(final Id tokenId) {
		final var merkleEntityId = new MerkleEntityId(tokenId.getShard(), tokenId.getRealm(), tokenId.getNum());
		var merkleToken = tokens.get().get(merkleEntityId);

		return merkleToken;
	}

	private void validateUsable(
			Id tokenId,
			ResponseCodeEnum keyFailure,
			Function<MerkleToken, Optional<JKey>> controlKeyFn
	) {

		var merkleToken = getMerkleToken(tokenId);

		validateTrue(merkleToken != null, INVALID_TOKEN_ID);
		validateFalse(merkleToken.isDeleted(), TOKEN_WAS_DELETED);

		validateFalse(controlKeyFn.apply(merkleToken).isEmpty(), keyFailure);
	}

	private void validateUsable(MerkleTokenRelStatus merkleTokenRelStatus) {
		validateTrue(merkleTokenRelStatus != null, TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);
	}

	private void mapModelChangesToMutable(Token token, MerkleToken mutableToken) {
		final var newAutoRenewAccount = token.getAutoRenewAccount();
		if (newAutoRenewAccount != null) {
			mutableToken.setAutoRenewAccount(new EntityId(newAutoRenewAccount.getId()));
		}
		mutableToken.setTreasury(new EntityId(token.getTreasury().getId()));
		mutableToken.setTotalSupply(token.getTotalSupply());
		mutableToken.setAccountsFrozenByDefault(token.isFrozenByDefault());
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
		token.setKycKey(immutableToken.getKycKey());
		token.setFreezeKey(immutableToken.getFreezeKey());
		token.setSupplyKey(immutableToken.getSupplyKey());
		token.setFrozenByDefault(immutableToken.accountsAreFrozenByDefault());
	}

	private void alertTokenBackingStoreOfNew(TokenRelationship newRel) {
		final var tokenId = newRel.getToken().getId();
		final var accountId = newRel.getAccount().getId();
		backingTokenRels.addToExistingRels(Pair.of(
				AccountID.newBuilder()
						.setShardNum(accountId.getShard())
						.setRealmNum(accountId.getRealm())
						.setAccountNum(accountId.getNum())
						.build(),
				TokenID.newBuilder()
						.setShardNum(tokenId.getShard())
						.setRealmNum(tokenId.getRealm())
						.setTokenNum(tokenId.getNum())
						.build()));
	}

	private MerkleToken createMerkleToken(TokenCreateTransactionBody op, Account treasuryAccount, long now) {
		var freezeKey = asUsableFcKey(op.getFreezeKey());
		var adminKey = asUsableFcKey(op.getAdminKey());
		var kycKey = asUsableFcKey(op.getKycKey());
		var wipeKey = asUsableFcKey(op.getWipeKey());
		var supplyKey = asUsableFcKey(op.getSupplyKey());
		var expiry = expiryOf(op, now);
		MerkleToken pendingCreation = new MerkleToken(
				expiry,
				op.getInitialSupply(),
				op.getDecimals(),
				op.getSymbol(),
				op.getName(),
				op.getFreezeDefault(),
				kycKey.isEmpty(),
				new EntityId(treasuryAccount.getId()));

		pendingCreation.setMemo(op.getMemo());
		adminKey.ifPresent(pendingCreation::setAdminKey);
		kycKey.ifPresent(pendingCreation::setKycKey);
		wipeKey.ifPresent(pendingCreation::setWipeKey);
		freezeKey.ifPresent(pendingCreation::setFreezeKey);
		supplyKey.ifPresent(pendingCreation::setSupplyKey);

		if(op.hasCustomFees()) {
			// TODO
		}

		if (op.hasAutoRenewAccount()) {
			pendingCreation.setAutoRenewAccount(fromGrpcAccountId(op.getAutoRenewAccount()));
			pendingCreation.setAutoRenewPeriod(op.getAutoRenewPeriod().getSeconds());
		}
		return pendingCreation;
	}

	private long expiryOf(TokenCreateTransactionBody request, long now) {
		return request.hasAutoRenewAccount()
				? now + request.getAutoRenewPeriod().getSeconds()
				: request.getExpiry().getSeconds();
	}
}
