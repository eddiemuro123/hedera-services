package com.hedera.services.usage.crypto;

/*-
 * ‌
 * Hedera Services API Fees
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
import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import com.hedera.services.test.IdUtils;
import com.hedera.services.test.KeyUtils;
import com.hedera.services.usage.BaseTransactionMeta;
import com.hedera.services.usage.EstimatorFactory;
import com.hedera.services.usage.QueryUsage;
import com.hedera.services.usage.SigUsage;
import com.hedera.services.usage.TxnUsageEstimator;
import com.hedera.services.usage.file.FileOpsUsage;
import com.hedera.services.usage.state.UsageAccumulator;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoGetInfoQuery;
import com.hederahashgraph.api.proto.java.CryptoUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.fee.FeeBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static com.hedera.services.test.UsageUtils.A_USAGES_MATRIX;
import static com.hedera.services.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.services.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.services.usage.crypto.entities.CryptoEntitySizes.CRYPTO_ENTITY_SIZES;
import static com.hedera.services.usage.token.entities.TokenEntitySizes.TOKEN_ENTITY_SIZES;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_STATE_PROOF;
import static com.hederahashgraph.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hederahashgraph.fee.FeeBuilder.BOOL_SIZE;
import static com.hederahashgraph.fee.FeeBuilder.INT_SIZE;
import static com.hederahashgraph.fee.FeeBuilder.LONG_SIZE;
import static com.hederahashgraph.fee.FeeBuilder.getAccountKeyStorageSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CryptoOpsUsageTest {
	private int numTokenRels = 3;
	private long secs = 500_000L;
	private long now = 1_234_567L;
	private long expiry = now + secs;
	private Key key = KeyUtils.A_COMPLEX_KEY;
	private String memo = "That abler soul, which thence doth flow";
	private AccountID proxy = IdUtils.asAccount("0.0.75231");
	private int maxAutoAssociations = 123;
	private int numSigs = 3, sigSize = 100, numPayerKeys = 1;
	private SigUsage sigUsage = new SigUsage(numSigs, sigSize, numPayerKeys);

	private EstimatorFactory factory;
	private TxnUsageEstimator base;
	private Function<ResponseType, QueryUsage> queryEstimatorFactory;
	private QueryUsage queryBase;

	private CryptoCreateTransactionBody creationOp;
	private CryptoUpdateTransactionBody updateOp;
	private TransactionBody txn;
	private Query query;

	private CryptoOpsUsage subject = new CryptoOpsUsage();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		base = mock(TxnUsageEstimator.class);
		given(base.get()).willReturn(A_USAGES_MATRIX);
		queryBase = mock(QueryUsage.class);
		given(queryBase.get()).willReturn(A_USAGES_MATRIX);

		factory = mock(EstimatorFactory.class);
		given(factory.get(any(), any(), any())).willReturn(base);
		queryEstimatorFactory = mock(Function.class);
		given(queryEstimatorFactory.apply(ANSWER_STATE_PROOF)).willReturn(queryBase);

		CryptoOpsUsage.txnEstimateFactory = factory;
		CryptoOpsUsage.queryEstimateFactory = queryEstimatorFactory;
	}

	@AfterEach
	void cleanup() {
		CryptoOpsUsage.txnEstimateFactory = TxnUsageEstimator::new;
		CryptoOpsUsage.queryEstimateFactory = QueryUsage::new;
	}

	@Test
	void estimatesInfoAsExpected() {
		givenInfoOp();
		// and:
		var ctx = ExtantCryptoContext.newBuilder()
				.setCurrentExpiry(expiry)
				.setCurrentMemo(memo)
				.setCurrentKey(key)
				.setCurrentlyHasProxy(true)
				.setCurrentNumTokenRels(numTokenRels)
				.setCurrentMaxAutomaticAssociations(maxAutoAssociations)
				.build();
		// and:
		given(queryBase.get()).willReturn(A_USAGES_MATRIX);

		// when:
		var estimate = subject.cryptoInfoUsage(query, ctx);

		// then:
		assertSame(A_USAGES_MATRIX, estimate);
		// and:
		verify(queryBase).addTb(BASIC_ENTITY_ID_SIZE);
		verify(queryBase).addRb(
				CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr()
						+ BASIC_ENTITY_ID_SIZE
						+ memo.length()
						+ getAccountKeyStorageSize(key)
						+ numTokenRels * TOKEN_ENTITY_SIZES.bytesUsedPerAccountRelationship());
	}

	@Test
	void accumulatesBptAndRbhAsExpectedForCryptoCreateWithMaxAutoAssociations() {
		givenCreationOpWithMaxAutoAssociaitons();
		final ByteString canonicalSig = ByteString.copyFromUtf8(
				"0123456789012345678901234567890123456789012345678901234567890123");
		final SignatureMap onePairSigMap = SignatureMap.newBuilder()
				.addSigPair(SignaturePair.newBuilder()
						.setPubKeyPrefix(ByteString.copyFromUtf8("a"))
						.setEd25519(canonicalSig))
				.build();
		final SigUsage singleSigUsage = new SigUsage(
				1, onePairSigMap.getSerializedSize(), 1);
		final var opMeta = new CryptoCreateMeta(txn.getCryptoCreateAccount());
		final var baseMeta = new BaseTransactionMeta(memo.length(), 0);

		var actual = new UsageAccumulator();
		var expected = new UsageAccumulator();

		var baseSize = memo.length() + getAccountKeyStorageSize(key) + BASIC_ENTITY_ID_SIZE + INT_SIZE;
		expected.resetForTransaction(baseMeta, singleSigUsage);
		expected.addBpt(baseSize + 2 * LONG_SIZE + BOOL_SIZE);
		expected.addRbs((CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr() + baseSize) * secs);
		expected.addRbs(maxAutoAssociations * secs * CryptoOpsUsage.CREATE_SLOT_MULTIPLIER);
		expected.addNetworkRbs(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());

		subject.cryptoCreateUsage(singleSigUsage, baseMeta, opMeta, actual);

		assertEquals(expected, actual);
	}

	@Test
	void accumulatesBptAndRbhAsExpectedForCryptoCreateWithoutMaxAutoAssociations() {
		givenCreationOpWithOutMaxAutoAssociaitons();
		final ByteString canonicalSig = ByteString.copyFromUtf8(
				"0123456789012345678901234567890123456789012345678901234567890123");
		final SignatureMap onePairSigMap = SignatureMap.newBuilder()
				.addSigPair(SignaturePair.newBuilder()
						.setPubKeyPrefix(ByteString.copyFromUtf8("a"))
						.setEd25519(canonicalSig))
				.build();
		final SigUsage singleSigUsage = new SigUsage(
				1, onePairSigMap.getSerializedSize(), 1);
		final var opMeta = new CryptoCreateMeta(txn.getCryptoCreateAccount());
		final var baseMeta = new BaseTransactionMeta(memo.length(), 0);

		var actual = new UsageAccumulator();
		var expected = new UsageAccumulator();

		var baseSize = memo.length() + getAccountKeyStorageSize(key) + BASIC_ENTITY_ID_SIZE;
		expected.resetForTransaction(baseMeta, singleSigUsage);
		expected.addBpt(baseSize + 2 * LONG_SIZE + BOOL_SIZE);
		expected.addRbs((CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr() + baseSize) * secs);
		expected.addNetworkRbs(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());

		subject.cryptoCreateUsage(singleSigUsage, baseMeta, opMeta, actual);

		assertEquals(expected, actual);
	}

	@Test
	void estimatesAutoRenewAsExpected() {
		var expectedRbsUsedInRenewal =
				(basicReprBytes() + (numTokenRels * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr()));

		var ctx = ExtantCryptoContext.newBuilder()
				.setCurrentExpiry(expiry)
				.setCurrentMemo(memo)
				.setCurrentKey(key)
				.setCurrentlyHasProxy(true)
				.setCurrentNumTokenRels(numTokenRels)
				.setCurrentMaxAutomaticAssociations(maxAutoAssociations)
				.build();

		var estimate = subject.cryptoAutoRenewRb(ctx);

		assertEquals(expectedRbsUsedInRenewal, estimate);
	}

	@Test
	void estimatesUpdateWithAutoAssociationsAsExpected() {
		givenUpdateOpWithMaxAutoAssociations();
		var expected = new UsageAccumulator();
		var baseMeta = new BaseTransactionMeta(memo.length(), 0);
		var opMeta = new CryptoUpdateMeta(txn.getCryptoUpdateAccount(),
				txn.getTransactionID().getTransactionValidStart().getSeconds());

		expected.resetForTransaction(baseMeta, sigUsage);

		Key oldKey = FileOpsUsage.asKey(KeyUtils.A_KEY_LIST.getKeyList());
		long oldExpiry = expiry - 1_234L;
		String oldMemo = "Lettuce";
		int oldMaxAutoAssociations = maxAutoAssociations - 5;

		var ctx = ExtantCryptoContext.newBuilder()
				.setCurrentExpiry(oldExpiry)
				.setCurrentMemo(oldMemo)
				.setCurrentKey(oldKey)
				.setCurrentlyHasProxy(false)
				.setCurrentNumTokenRels(numTokenRels)
				.setCurrentMaxAutomaticAssociations(oldMaxAutoAssociations)
				.build();

		long keyBytesUsed = getAccountKeyStorageSize(key);
		long msgBytesUsed = BASIC_ENTITY_ID_SIZE + memo.getBytes().length + keyBytesUsed
				+ LONG_SIZE + BASIC_ENTITY_ID_SIZE + INT_SIZE;

		expected.addBpt(msgBytesUsed);

		long newVariableBytes = memo.getBytes().length + keyBytesUsed + BASIC_ENTITY_ID_SIZE;
		long tokenRelBytes = numTokenRels * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr();
		long sharedFixedBytes = CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr() + tokenRelBytes;
		long newLifetime = ESTIMATOR_UTILS.relativeLifetime(txn, expiry);
		long oldLifetime = ESTIMATOR_UTILS.relativeLifetime(txn, oldExpiry);
		long rbsDelta = ESTIMATOR_UTILS.changeInBsUsage(
				CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr()
						+ ctx.currentNonBaseRb()
						+ ctx.currentNumTokenRels() * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr(),
				oldLifetime,
				sharedFixedBytes + newVariableBytes,
				newLifetime);
		if (rbsDelta > 0) {
			expected.addRbs(rbsDelta);
		}

		final var slotDelta = ESTIMATOR_UTILS.changeInBsUsage(
				oldMaxAutoAssociations * CryptoOpsUsage.UPDATE_SLOT_MULTIPLIER,
				oldLifetime,
				maxAutoAssociations * CryptoOpsUsage.UPDATE_SLOT_MULTIPLIER,
				newLifetime);
		expected.addRbs(slotDelta);

		var actual = new UsageAccumulator();

		subject.cryptoUpdateUsage(sigUsage, baseMeta, opMeta, ctx, actual);

		assertEquals(expected, actual);
	}

	@Test
	void estimatesUpdateWithOutAutoAssociationsAsExpected() {
		givenUpdateOpWithOutMaxAutoAssociations();
		var expected = new UsageAccumulator();
		var baseMeta = new BaseTransactionMeta(memo.length(), 0);
		var opMeta = new CryptoUpdateMeta(txn.getCryptoUpdateAccount(),
				txn.getTransactionID().getTransactionValidStart().getSeconds());

		expected.resetForTransaction(baseMeta, sigUsage);

		Key oldKey = FileOpsUsage.asKey(KeyUtils.A_KEY_LIST.getKeyList());
		long oldExpiry = expiry - 1_234L;
		String oldMemo = "Lettuce";

		var ctx = ExtantCryptoContext.newBuilder()
				.setCurrentExpiry(oldExpiry)
				.setCurrentMemo(oldMemo)
				.setCurrentKey(oldKey)
				.setCurrentlyHasProxy(false)
				.setCurrentNumTokenRels(numTokenRels)
				.setCurrentMaxAutomaticAssociations(maxAutoAssociations)
				.build();

		long keyBytesUsed = getAccountKeyStorageSize(key);
		long msgBytesUsed = BASIC_ENTITY_ID_SIZE + memo.getBytes().length + keyBytesUsed
				+ LONG_SIZE + BASIC_ENTITY_ID_SIZE;

		expected.addBpt(msgBytesUsed);

		long newVariableBytes = memo.getBytes().length + keyBytesUsed + BASIC_ENTITY_ID_SIZE;
		long tokenRelBytes = numTokenRels * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr();
		long sharedFixedBytes = CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr() + tokenRelBytes;
		long newLifetime = ESTIMATOR_UTILS.relativeLifetime(txn, expiry);
		long oldLifetime = ESTIMATOR_UTILS.relativeLifetime(txn, oldExpiry);
		long rbsDelta = ESTIMATOR_UTILS.changeInBsUsage(
				CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr()
						+ ctx.currentNonBaseRb()
						+ ctx.currentNumTokenRels() * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr(),
				oldLifetime,
				sharedFixedBytes + newVariableBytes,
				newLifetime);
		if (rbsDelta > 0) {
			expected.addRbs(rbsDelta);
		}
		final var slotDelta = ESTIMATOR_UTILS.changeInBsUsage(
				maxAutoAssociations * CryptoOpsUsage.UPDATE_SLOT_MULTIPLIER,
				oldLifetime,
				maxAutoAssociations * CryptoOpsUsage.UPDATE_SLOT_MULTIPLIER,
				newLifetime);
		expected.addRbs(slotDelta);

		var actual = new UsageAccumulator();

		subject.cryptoUpdateUsage(sigUsage, baseMeta, opMeta, ctx, actual);

		assertEquals(expected, actual);
	}

	private long basicReprBytes() {
		return CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr()
				/* The proxy account */
				+ BASIC_ENTITY_ID_SIZE
				+ memo.length()
				+ FeeBuilder.getAccountKeyStorageSize(key)
				+ (maxAutoAssociations != 0 ? INT_SIZE : 0);
	}

	private void givenUpdateOpWithOutMaxAutoAssociations() {
		updateOp = CryptoUpdateTransactionBody.newBuilder()
				.setExpirationTime(Timestamp.newBuilder().setSeconds(expiry))
				.setProxyAccountID(proxy)
				.setMemo(StringValue.newBuilder().setValue(memo))
				.setKey(key)
				.build();
		setUpdateTxn();
	}

	private void givenUpdateOpWithMaxAutoAssociations() {
		updateOp = CryptoUpdateTransactionBody.newBuilder()
				.setExpirationTime(Timestamp.newBuilder().setSeconds(expiry))
				.setProxyAccountID(proxy)
				.setMemo(StringValue.newBuilder().setValue(memo))
				.setKey(key)
				.setMaxAutomaticTokenAssociations(Int32Value.of(maxAutoAssociations))
				.build();
		setUpdateTxn();
	}

	private void setUpdateTxn() {
		txn = TransactionBody.newBuilder()
				.setTransactionID(TransactionID.newBuilder()
						.setTransactionValidStart(Timestamp.newBuilder()
								.setSeconds(now)))
				.setCryptoUpdateAccount(updateOp)
				.build();
	}

	private void givenCreationOpWithOutMaxAutoAssociaitons() {
		creationOp = CryptoCreateTransactionBody.newBuilder()
				.setProxyAccountID(proxy)
				.setAutoRenewPeriod(Duration.newBuilder().setSeconds(secs).build())
				.setMemo(memo)
				.setKey(key)
				.build();
		setCreateTxn();
	}

	private void givenCreationOpWithMaxAutoAssociaitons() {
		creationOp = CryptoCreateTransactionBody.newBuilder()
				.setProxyAccountID(proxy)
				.setAutoRenewPeriod(Duration.newBuilder().setSeconds(secs).build())
				.setMemo(memo)
				.setKey(key)
				.setMaxAutomaticTokenAssociations(maxAutoAssociations)
				.build();
		setCreateTxn();
	}

	private void setCreateTxn() {
		txn = TransactionBody.newBuilder()
				.setTransactionID(TransactionID.newBuilder()
						.setTransactionValidStart(Timestamp.newBuilder()
								.setSeconds(now)))
				.setCryptoCreateAccount(creationOp).build();
	}

	private void givenInfoOp() {
		query = Query.newBuilder()
				.setCryptoGetInfo(CryptoGetInfoQuery.newBuilder()
						.setHeader(QueryHeader.newBuilder()
								.setResponseType(ANSWER_STATE_PROOF)))
				.build();
	}
}
