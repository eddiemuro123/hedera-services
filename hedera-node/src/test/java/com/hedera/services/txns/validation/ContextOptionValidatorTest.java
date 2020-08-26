package com.hedera.services.txns.validation;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
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
import com.hedera.services.state.merkle.MerkleTopic;
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.utils.SignedTxnAccessor;
import com.hedera.test.factories.accounts.MapValueFactory;
import com.hedera.test.factories.scenarios.TxnHandlingScenario;
import com.hedera.test.factories.topics.TopicFactory;
import com.hedera.test.factories.txns.SignedTxnFactory;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FileGetInfoResponse;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TopicID;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransferList;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.legacy.core.jproto.JFileInfo;
import com.hedera.services.legacy.core.jproto.JKey;
import com.swirlds.fcmap.FCMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;
import java.time.Instant;
import java.util.Optional;

import static com.hedera.test.utils.IdUtils.asFile;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.*;
import static com.hedera.test.utils.TxnUtils.withAdjustments;
import static org.junit.jupiter.api.Assertions.*;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;
import static com.hedera.services.state.merkle.MerkleEntityId.fromContractId;

@RunWith(JUnitPlatform.class)
public class ContextOptionValidatorTest {
	final private Key key = SignedTxnFactory.DEFAULT_PAYER_KT.asKey();
	final private Instant now = Instant.now();
	final private AccountID a = AccountID.newBuilder().setAccountNum(9_999L).build();
	final private MerkleAccount aV = MapValueFactory.newAccount().get();
	final private AccountID b = AccountID.newBuilder().setAccountNum(8_999L).build();
	final private AccountID c = AccountID.newBuilder().setAccountNum(7_999L).build();
	final private AccountID d = AccountID.newBuilder().setAccountNum(6_999L).build();
	final private AccountID missing = AccountID.newBuilder().setAccountNum(1_234L).build();
	final private ContractID missingContract = ContractID.newBuilder().setContractNum(5_431L).build();
	final private AccountID deleted = AccountID.newBuilder().setAccountNum(2_234L).build();
	final private MerkleAccount deletedV = MapValueFactory.newAccount().deleted(true).get();
	final private ContractID contract = ContractID.newBuilder().setContractNum(5_432L).build();
	final private MerkleAccount contractV = MapValueFactory.newAccount().isSmartContract(true).get();
	final private ContractID deletedContract = ContractID.newBuilder().setContractNum(4_432L).build();
	final private MerkleAccount deletedContractV =
			MapValueFactory.newAccount().isSmartContract(true).deleted(true).get();
	final private TopicID missingTopicId = TopicID.newBuilder().setTopicNum(1_234L).build();
	final private TopicID deletedTopicId = TopicID.newBuilder().setTopicNum(2_345L).build();
	final private TopicID expiredTopicId = TopicID.newBuilder().setTopicNum(3_456L).build();
	final private TopicID topicId = TopicID.newBuilder().setTopicNum(4_567L).build();

	private MerkleTopic missingMerkleTopic;
	private MerkleTopic deletedMerkleTopic;
	private MerkleTopic expiredMerkleTopic;
	private MerkleTopic merkleTopic;
	private FCMap topics;
	private FCMap accounts;
	private HederaLedger ledger;
	private PropertySource properties;
	private TransactionContext txnCtx;
	private ContextOptionValidator subject;
	private JKey wacl;
	private JFileInfo attr;
	private JFileInfo deletedAttr;
	private StateView view;
	private long expiry = 2_000_000L;
	private FileID target = asFile("0.0.123");

	@BeforeEach
	private void setup() throws Exception {
		txnCtx = mock(TransactionContext.class);
		given(txnCtx.consensusTime()).willReturn(now);
		ledger = mock(HederaLedger.class);
		given(ledger.isSmartContract(a)).willReturn(false);
		given(ledger.isSmartContract(b)).willReturn(false);
		given(ledger.isSmartContract(c)).willReturn(true);
		given(ledger.isSmartContract(d)).willReturn(false);
		properties = mock(PropertySource.class);
		given(properties.getIntProperty("hedera.transaction.maxMemoUtf8Bytes")).willReturn(100);
		accounts = mock(FCMap.class);
		given(accounts.get(MerkleEntityId.fromAccountId(a))).willReturn(aV);
		given(accounts.get(MerkleEntityId.fromAccountId(deleted))).willReturn(deletedV);
		given(accounts.get(fromContractId(contract))).willReturn(contractV);
		given(accounts.get(fromContractId(deletedContract))).willReturn(deletedContractV);

		topics = mock(FCMap.class);
		missingMerkleTopic = TopicFactory.newTopic().memo("I'm not here").get();
		deletedMerkleTopic = TopicFactory.newTopic().deleted(true).get();
		expiredMerkleTopic = TopicFactory.newTopic().expiry(now.minusSeconds(555L).getEpochSecond()).get();
		merkleTopic = TopicFactory.newTopic().memo("Hi, over here!").expiry(now.plusSeconds(555L).getEpochSecond()).get();
		given(topics.get(MerkleEntityId.fromTopicId(topicId))).willReturn(merkleTopic);
		given(topics.get(MerkleEntityId.fromTopicId(missingTopicId))).willReturn(null);
		given(topics.get(MerkleEntityId.fromTopicId(deletedTopicId))).willReturn(deletedMerkleTopic);
		given(topics.get(MerkleEntityId.fromTopicId(expiredTopicId))).willReturn(expiredMerkleTopic);

		wacl = TxnHandlingScenario.SIMPLE_NEW_WACL_KT.asJKey();
		attr = new JFileInfo(false, wacl, expiry);
		deletedAttr = new JFileInfo(true, wacl, expiry);
		view = mock(StateView.class);

		subject = new ContextOptionValidator(ledger, properties, txnCtx);
	}

	private FileGetInfoResponse.FileInfo asMinimalInfo(JFileInfo meta) throws Exception {
		return FileGetInfoResponse.FileInfo.newBuilder()
				.setDeleted(meta.isDeleted())
				.setKeys(JKey.mapJKey(meta.getWacl()).getKeyList())
				.build();
	}

	@Test
	public void recognizesOkFile() throws Exception {
		given(view.infoForFile(target)).willReturn(Optional.of(asMinimalInfo(attr)));

		// when:
		var status = subject.queryableFileStatus(target, view);

		// then:
		assertEquals(OK, status);
	}

	@Test
	public void recognizesDeletedFile() throws Exception {
		given(view.infoForFile(target)).willReturn(Optional.of(asMinimalInfo(deletedAttr)));

		// when:
		var status = subject.queryableFileStatus(target, view);

		// then:
		assertEquals(OK, status);
		assertTrue(deletedAttr.isDeleted());
	}

	@Test
	public void recognizesMissingFile() {
		given(view.infoForFile(target)).willReturn(Optional.empty());

		// when:
		var status = subject.queryableFileStatus(target, view);

		// then:
		assertEquals(INVALID_FILE_ID, status);
	}

	@Test
	public void usesConsensusTimeForTopicExpiry() {
		// expect:
		assertTrue(subject.isExpired(expiredMerkleTopic));
		assertFalse(subject.isExpired(merkleTopic));
	}

	@Test
	public void recognizesMissingTopic() {
		// expect:
		assertEquals(INVALID_TOPIC_ID, subject.queryableTopicStatus(missingTopicId, topics));
	}

	@Test
	public void recognizesDeletedTopicStatus() {
		// expect:
		assertEquals(INVALID_TOPIC_ID, subject.queryableTopicStatus(deletedTopicId, topics));
	}

	@Test
	public void ignoresExpiredTopicStatus() {
		// expect:
		assertEquals(OK, subject.queryableTopicStatus(expiredTopicId, topics));
	}

	@Test
	public void recognizesOkTopicStatus() {
		// expect:
		assertEquals(OK, subject.queryableTopicStatus(topicId, topics));
	}

	@Test
	public void recognizesMissingAccountStatus() {
		// expect:
		assertEquals(INVALID_ACCOUNT_ID, subject.queryableAccountStatus(missing, accounts));
	}

	@Test
	public void recognizesDeletedAccountStatus() {
		// expect:
		assertEquals(ACCOUNT_DELETED, subject.queryableAccountStatus(deleted, accounts));
	}

	@Test
	public void recognizesOutOfPlaceAccountStatus() {
		// expect:
		assertEquals(
				INVALID_ACCOUNT_ID,
				subject.queryableAccountStatus(IdUtils.asAccount("0.0.5432"), accounts));
	}

	@Test
	public void recognizesOkAccountStatus() {
		// expect:
		assertEquals(OK, subject.queryableAccountStatus(a, accounts));
	}

	@Test
	public void recognizesMissingContractStatus() {
		// expect:
		assertEquals(
				INVALID_CONTRACT_ID,
				subject.queryableContractStatus(missingContract, accounts));
	}

	@Test
	public void recognizesDeletedContractStatus() {
		// expect:
		assertEquals(CONTRACT_DELETED, subject.queryableContractStatus(deletedContract, accounts));
	}

	@Test
	public void recognizesOutOfPlaceContractStatus() {
		// expect:
		assertEquals(
				INVALID_CONTRACT_ID,
				subject.queryableContractStatus(IdUtils.asContract("0.0.9999"), accounts));
	}

	@Test
	public void recognizesOkContractStatus() {
		// expect:
		assertEquals(OK, subject.queryableContractStatus(contract, accounts));
	}

	@Test
	public void rejectsBriefTxnDuration() {
		given(properties.getLongProperty("hedera.transaction.minValidDuration")).willReturn(2L);
		given(properties.getLongProperty("hedera.transaction.maxValidDuration")).willReturn(10L);

		// expect:
		assertFalse(subject.isValidTxnDuration(1L));
		// and:
		verify(properties).getLongProperty("hedera.transaction.minValidDuration");
	}

	@Test
	public void rejectsProlongedTxnDuration() {
		given(properties.getLongProperty("hedera.transaction.minValidDuration")).willReturn(2L);
		given(properties.getLongProperty("hedera.transaction.maxValidDuration")).willReturn(10L);

		// expect:
		assertFalse(subject.isValidTxnDuration(11L));
		// and:
		verify(properties).getLongProperty("hedera.transaction.minValidDuration");
		verify(properties).getLongProperty("hedera.transaction.maxValidDuration");
	}

	@Test
	public void rejectsBriefAutoRenewPeriod() {
		// setup:
		Duration autoRenewPeriod = Duration.newBuilder().setSeconds(55L).build();

		given(properties.getLongProperty("ledger.autoRenewPeriod.minDuration")).willReturn(1_000L);
		given(properties.getLongProperty("ledger.autoRenewPeriod.maxDuration")).willReturn(1_000_000L);

		// expect:
		assertFalse(subject.isValidAutoRenewPeriod(autoRenewPeriod));
		// and:
		verify(properties).getLongProperty("ledger.autoRenewPeriod.minDuration");
	}

	@Test
	public void acceptsReasonablePeriod() {
		// setup:
		Duration autoRenewPeriod = Duration.newBuilder().setSeconds(500_000L).build();

		given(properties.getLongProperty("ledger.autoRenewPeriod.minDuration")).willReturn(1_000L);
		given(properties.getLongProperty("ledger.autoRenewPeriod.maxDuration")).willReturn(1_000_000L);

		// expect:
		assertTrue(subject.isValidAutoRenewPeriod(autoRenewPeriod));
		// and:
		verify(properties).getLongProperty("ledger.autoRenewPeriod.minDuration");
		verify(properties).getLongProperty("ledger.autoRenewPeriod.maxDuration");
	}

	@Test
	public void rejectsProlongedAutoRenewPeriod() {
		// setup:
		Duration autoRenewPeriod = Duration.newBuilder().setSeconds(5_555_555L).build();

		given(properties.getLongProperty("ledger.autoRenewPeriod.minDuration")).willReturn(1_000L);
		given(properties.getLongProperty("ledger.autoRenewPeriod.maxDuration")).willReturn(1_000_000L);

		// expect:
		assertFalse(subject.isValidAutoRenewPeriod(autoRenewPeriod));
		// and:
		verify(properties).getLongProperty("ledger.autoRenewPeriod.minDuration");
		verify(properties).getLongProperty("ledger.autoRenewPeriod.maxDuration");
	}

	@Test
	public void allowsReasonableLength() {
		// setup:
		TransferList wrapper = withAdjustments(a, 2L, b, -3L, d, 1L);

		given(properties.getIntProperty("ledger.transfers.maxLen")).willReturn(3);

		// expect:
		assertTrue(subject.isAcceptableLength(wrapper));
		// and:
		verify(properties).getIntProperty("ledger.transfers.maxLen");
	}

	@Test
	public void rejectsUnreasonableLength() {
		// setup:
		TransferList wrapper = withAdjustments(a, 2L, b, -3L, d, 1L);

		given(properties.getIntProperty("ledger.transfers.maxLen")).willReturn(2);

		// expect:
		assertFalse(subject.isAcceptableLength(wrapper));
		// and:
		verify(properties).getIntProperty("ledger.transfers.maxLen");
	}

	@Test
	public void recognizesCleanTransfers() {
		// given:
		TransferList wrapper = withAdjustments(a, 2L, b, -3L, d, 1L);

		// expect:
		assertTrue(subject.hasOnlyCryptoAccounts(wrapper));
	}

	@Test
	public void recognizesContractInTransfer() {
		// given:
		TransferList wrapper = withAdjustments(a, 2L, c, -3L, d, 1L);

		// expect:
		assertFalse(subject.hasOnlyCryptoAccounts(wrapper));
	}

	@Test
	public void acceptsMappableKey() {
		// expect:
		assertTrue(subject.hasGoodEncoding(key));
	}

	@Test
	public void rejectsUnmappableKey() {
		// expect:
		assertFalse(subject.hasGoodEncoding(Key.getDefaultInstance()));
	}

	@Test
	public void acceptsEmptyKeyList() {
		// expect:
		assertTrue(subject.hasGoodEncoding(Key.newBuilder().setKeyList(KeyList.getDefaultInstance()).build()));
	}

	@Test
	public void allowsAnyFutureExpiry() {
		// expect:
		assertTrue(subject.isValidExpiry(
				Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano() + 1).build()));
		// and:
		verify(txnCtx).consensusTime();
	}

	@Test
	public void rejectsAnyNonFutureExpiry() {
		// expect:
		assertFalse(subject.isValidExpiry(
				Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build()));
		// and:
		verify(txnCtx).consensusTime();
	}

	@Test
	public void rejectsLongEntityMemo() {
		// expect:
		assertFalse(subject.isValidEntityMemo(new String(new char[101])));
	}

	@Test
	public void accepts100ByteEntityMemo() {
		// expect:
		assertTrue(subject.isValidEntityMemo(new String(new char[100])));
	}

	@Test
	public void acceptsNullEntityMemo() {
		// expect:
		assertTrue(subject.isValidEntityMemo(null));
	}

	@Test
	public void recognizesExpiredCondition() {
		SignedTxnAccessor accessor = mock(SignedTxnAccessor.class);

		// given:
		long validDuration = 1_000L;
		Instant validStart = Instant.ofEpochSecond(1_234_567L);
		Instant consensusTime = Instant.ofEpochSecond(validStart.getEpochSecond() + validDuration + 1);
		// and:
		TransactionID txnId = TransactionID.newBuilder()
				.setTransactionValidStart(Timestamp.newBuilder()
						.setSeconds(validStart.getEpochSecond()))
				.build();
		TransactionBody txn = TransactionBody.newBuilder()
				.setTransactionID(txnId)
				.setTransactionValidDuration(Duration.newBuilder().setSeconds(validDuration))
				.build();
		// and:
		given(accessor.getTxn()).willReturn(txn);
		given(accessor.getTxnId()).willReturn(txnId);

		// when:
		ResponseCodeEnum status = subject.chronologyStatus(accessor, consensusTime);

		// then:
		assertEquals(TRANSACTION_EXPIRED, status);
		// and:
		assertEquals(TRANSACTION_EXPIRED,
				subject.chronologyStatusForTxn(validStart, validDuration, consensusTime));
	}

	@Test
	public void recognizesFutureValidStartStart() {
		SignedTxnAccessor accessor = mock(SignedTxnAccessor.class);

		// given:
		long validDuration = 1_000L;
		Instant consensusTime = Instant.ofEpochSecond(1_234_567L);
		Instant validStart = Instant.ofEpochSecond(consensusTime.plusSeconds(1L).getEpochSecond());
		// and:
		TransactionID txnId = TransactionID.newBuilder()
				.setTransactionValidStart(Timestamp.newBuilder()
						.setSeconds(validStart.getEpochSecond()))
				.build();
		TransactionBody txn = TransactionBody.newBuilder()
				.setTransactionID(txnId)
				.setTransactionValidDuration(Duration.newBuilder().setSeconds(validDuration))
				.build();
		// and:
		given(accessor.getTxn()).willReturn(txn);
		given(accessor.getTxnId()).willReturn(txnId);

		// when:
		ResponseCodeEnum status = subject.chronologyStatus(accessor, consensusTime);

		// then:
		assertEquals(INVALID_TRANSACTION_START, status);
		// and:
		assertEquals(INVALID_TRANSACTION_START,
				subject.chronologyStatusForTxn(validStart, validDuration, consensusTime));
	}

	@Test
	public void acceptsOk() {
		SignedTxnAccessor accessor = mock(SignedTxnAccessor.class);

		// given:
		long validDuration = 1_000L;
		Instant consensusTime = Instant.ofEpochSecond(1_234_567L);
		Instant validStart = Instant.ofEpochSecond(consensusTime.minusSeconds(validDuration - 1).getEpochSecond());
		// and:
		TransactionID txnId = TransactionID.newBuilder()
				.setTransactionValidStart(Timestamp.newBuilder()
						.setSeconds(validStart.getEpochSecond()))
				.build();
		TransactionBody txn = TransactionBody.newBuilder()
				.setTransactionID(txnId)
				.setTransactionValidDuration(Duration.newBuilder().setSeconds(validDuration))
				.build();
		// and:
		given(accessor.getTxn()).willReturn(txn);
		given(accessor.getTxnId()).willReturn(txnId);

		// when:
		ResponseCodeEnum status = subject.chronologyStatus(accessor, consensusTime);

		// then:
		assertEquals(OK, status);
	}

	@Test
	public void rejectsImplausibleAccounts() {
		// given:
		var implausibleShard = AccountID.newBuilder().setShardNum(-1).build();
		var implausibleRealm = AccountID.newBuilder().setRealmNum(-1).build();
		var implausibleAccount = AccountID.newBuilder().setAccountNum(0).build();
		var plausibleAccount = IdUtils.asAccount("0.0.13257");

		// expect:
		assertFalse(subject.isPlausibleAccount(implausibleShard));
		assertFalse(subject.isPlausibleAccount(implausibleRealm));
		assertFalse(subject.isPlausibleAccount(implausibleAccount));
		assertTrue(subject.isPlausibleAccount(plausibleAccount));
	}

	@Test
	public void rejectsImplausibleTxnFee() {
		// expect:
		assertFalse(subject.isPlausibleTxnFee(-1));
		assertTrue(subject.isPlausibleTxnFee(0));
	}
}
