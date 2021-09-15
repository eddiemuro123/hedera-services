package com.hedera.services.txns.consensus;

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
import com.hedera.services.exceptions.InvalidTransactionException;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleTopic;
import com.hedera.services.store.AccountStore;
import com.hedera.services.store.TopicStore;
import com.hedera.services.store.models.Account;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hedera.test.factories.txns.SignedTxnFactory;
import com.hedera.test.utils.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ConsensusCreateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.swirlds.merkle.map.MerkleMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static com.hedera.test.factories.scenarios.TxnHandlingScenario.MISC_ACCOUNT;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.MISC_ACCOUNT_KT;
import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_ACCOUNT_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BAD_ENCODING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.verify;

@ExtendWith(MockitoExtension.class)
class MerkleTopicCreateTransitionLogicTest {
	private static final long VALID_AUTORENEW_PERIOD_SECONDS = 30 * 86400L;
	private static final long INVALID_AUTORENEW_PERIOD_SECONDS = -1L;
	private static final String TOO_LONG_MEMO = "too-long";
	private static final String VALID_MEMO = "memo";
	private static final AccountID NEW_TOPIC_ID = asAccount("7.6.54321");

	// key to be used as a valid admin or submit key.
	final private Key key = SignedTxnFactory.DEFAULT_PAYER_KT.asKey();
	final private AccountID payer = AccountID.newBuilder().setAccountNum(2_345L).build();
	private Instant consensusTimestamp;
	private TransactionBody transactionBody;
	
	@Mock
	private TransactionContext transactionContext;
	@Mock
	private PlatformTxnAccessor accessor;
	@Mock
	private OptionValidator validator;
	private TopicCreateTransitionLogic subject;
	private MerkleMap<EntityNum, MerkleAccount> accounts = new MerkleMap<>();
	private MerkleMap<EntityNum, MerkleTopic> topics = new MerkleMap<>();
	@Mock
	private EntityIdSource entityIdSource;
	@Mock
	private TopicStore topicStore;
	@Mock
	private AccountStore accountStore;
	@Mock
	private Account autoRenew;
	
	@BeforeEach
	private void setup() {
		consensusTimestamp = Instant.ofEpochSecond(1546304463);
		accounts.clear();
		topics.clear();

		subject = new TopicCreateTransitionLogic(
				topicStore, entityIdSource, validator, transactionContext, accountStore);
	}

	@Test
	void hasCorrectApplicability() {
		// given:
		givenValidTransactionWithAllOptions();

		// expect:
		assertTrue(subject.applicability().test(transactionBody));
		assertFalse(subject.applicability().test(TransactionBody.getDefaultInstance()));
	}

	@Test
	void syntaxCheckWithAdminKey() {
		// given:
		givenValidTransactionWithAllOptions();
		given(validator.hasGoodEncoding(key)).willReturn(true);

		// expect:
		assertEquals(OK, subject.semanticCheck().apply(transactionBody));
	}

	@Test
	void syntaxCheckWithInvalidAdminKey() {
		// given:
		givenValidTransactionWithAllOptions();
		given(validator.hasGoodEncoding(key)).willReturn(false);

		// expect:
		assertEquals(BAD_ENCODING, subject.semanticCheck().apply(transactionBody));
	}

	@Test
	void followsHappyPath() throws Throwable {
		// given:
		givenValidTransactionWithAllOptions();
		given(validator.memoCheck(anyString())).willReturn(OK);
		given(transactionContext.accessor()).willReturn(accessor);
		given(accessor.getTxn()).willReturn(transactionBody);
		given(accountStore.loadAccountOrFailWith(any(), any())).willReturn(autoRenew);
		given(autoRenew.isSmartContract()).willReturn(false);
		given(validator.isValidAutoRenewPeriod(Duration.newBuilder().setSeconds(VALID_AUTORENEW_PERIOD_SECONDS).build()))
				.willReturn(true);
		given(validator.hasGoodEncoding(any())).willReturn(true);
		given(transactionContext.consensusTime()).willReturn(consensusTimestamp);
		given(entityIdSource.newAccountId(any())).willReturn(NEW_TOPIC_ID);
		// when:
		subject.doStateTransition();
		// then:
		verify(topicStore).persistNew(any());
	}

	@Test
	void memoTooLong() {
		// given:
		givenTransactionWithTooLongMemo();
		given(validator.memoCheck(anyString())).willReturn(MEMO_TOO_LONG);
		given(transactionContext.accessor()).willReturn(accessor);
		given(accessor.getTxn()).willReturn(transactionBody);
		// when:
		TxnUtils.assertFailsWith(() -> subject.doStateTransition(), MEMO_TOO_LONG);
		// then:
		assertTrue(topics.isEmpty());
	}

	@Test
	void badSubmitKey() {
		// given:
		givenTransactionWithInvalidSubmitKey();
		given(validator.memoCheck(anyString())).willReturn(OK);
		given(transactionContext.accessor()).willReturn(accessor);
		given(accessor.getTxn()).willReturn(transactionBody);
		// when:
		TxnUtils.assertFailsWith(() -> subject.doStateTransition(), BAD_ENCODING);

		// then:
		assertTrue(topics.isEmpty());
	}

	@Test
	void missingAutoRenewPeriod() {
		// given:
		givenTransactionWithMissingAutoRenewPeriod();
		given(validator.memoCheck(anyString())).willReturn(OK);
		given(transactionContext.accessor()).willReturn(accessor);
		given(accessor.getTxn()).willReturn(transactionBody);
		// when:
		TxnUtils.assertFailsWith(() -> subject.doStateTransition(), INVALID_RENEWAL_PERIOD);

		// then:
		assertTrue(topics.isEmpty());
	}

	@Test
	void badAutoRenewPeriod() {
		// given:
		givenTransactionWithInvalidAutoRenewPeriod();
		given(validator.memoCheck(anyString())).willReturn(OK);
		given(transactionContext.accessor()).willReturn(accessor);
		given(accessor.getTxn()).willReturn(transactionBody);
		// when:
		TxnUtils.assertFailsWith(() -> subject.doStateTransition(), AUTORENEW_DURATION_NOT_IN_RANGE);

		// then:
		assertTrue(topics.isEmpty());
	}

	@Test
	void invalidAutoRenewAccountId() {
		// given:
		givenTransactionWithInvalidAutoRenewAccountId();
		given(validator.memoCheck(anyString())).willReturn(OK);
		given(transactionContext.accessor()).willReturn(accessor);
		given(accessor.getTxn()).willReturn(transactionBody);
		given(accountStore.loadAccountOrFailWith(any(), any())).willThrow(new InvalidTransactionException(INVALID_AUTORENEW_ACCOUNT));
		given(validator.isValidAutoRenewPeriod(Duration.newBuilder().setSeconds(VALID_AUTORENEW_PERIOD_SECONDS).build()))
				.willReturn(true);
		// when:
		TxnUtils.assertFailsWith(() -> subject.doStateTransition(), INVALID_AUTORENEW_ACCOUNT);


		// then:
		assertTrue(topics.isEmpty());
	}

	@Test
	void detachedAutoRenewAccountId() {
		// given:
		givenTransactionWithDetachedAutoRenewAccountId();
		given(validator.memoCheck(anyString())).willReturn(OK);
		given(transactionContext.accessor()).willReturn(accessor);
		given(accessor.getTxn()).willReturn(transactionBody);
		given(validator.isValidAutoRenewPeriod(Duration.newBuilder().setSeconds(VALID_AUTORENEW_PERIOD_SECONDS).build()))
				.willReturn(true);
		given(accountStore.loadAccountOrFailWith(any(), any())).willThrow(new InvalidTransactionException(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL));
		// when:
		TxnUtils.assertFailsWith(() -> subject.doStateTransition(), ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);

		// then:
		assertTrue(topics.isEmpty());
	}

	@Test
	void autoRenewAccountNotAllowed() {
		// given:
		givenTransactionWithAutoRenewAccountWithoutAdminKey();
		given(validator.memoCheck(anyString())).willReturn(OK);
		given(transactionContext.accessor()).willReturn(accessor);
		given(accessor.getTxn()).willReturn(transactionBody);
		given(validator.isValidAutoRenewPeriod(Duration.newBuilder().setSeconds(VALID_AUTORENEW_PERIOD_SECONDS).build()))
				.willReturn(true);
		given(accountStore.loadAccountOrFailWith(any(), any())).willReturn(autoRenew);
		given(autoRenew.isSmartContract()).willReturn(false);
		// when:
		TxnUtils.assertFailsWith(() -> subject.doStateTransition(), AUTORENEW_ACCOUNT_NOT_ALLOWED);

		// then:
		assertTrue(topics.isEmpty());
	}

	private void givenTransaction(ConsensusCreateTopicTransactionBody.Builder body) {
		transactionBody = TransactionBody.newBuilder()
				.setTransactionID(ourTxnId())
				.setConsensusCreateTopic(body.build())
				.build();
	}

	private ConsensusCreateTopicTransactionBody.Builder getBasicValidTransactionBodyBuilder() {
		return ConsensusCreateTopicTransactionBody.newBuilder()
				.setAutoRenewPeriod(Duration.newBuilder()
						.setSeconds(VALID_AUTORENEW_PERIOD_SECONDS).build());
	}

	private void givenValidTransactionWithAllOptions() {
		givenTransaction(
				getBasicValidTransactionBodyBuilder()
						.setMemo(VALID_MEMO)
						.setAdminKey(key)
						.setSubmitKey(key)
						.setAutoRenewAccount(MISC_ACCOUNT)
		);
	}

	private void givenTransactionWithTooLongMemo() {
		givenTransaction(
				getBasicValidTransactionBodyBuilder()
						.setMemo(TOO_LONG_MEMO)
		);
	}

	private void givenTransactionWithInvalidSubmitKey() {
		givenTransaction(
				getBasicValidTransactionBodyBuilder()
						.setSubmitKey(MISC_ACCOUNT_KT.asKey())
		);
		given(validator.hasGoodEncoding(MISC_ACCOUNT_KT.asKey())).willReturn(false);
	}

	private void givenTransactionWithInvalidAutoRenewPeriod() {
		givenTransaction(
				ConsensusCreateTopicTransactionBody.newBuilder()
						.setAutoRenewPeriod(Duration.newBuilder()
								.setSeconds(INVALID_AUTORENEW_PERIOD_SECONDS).build())
		);
	}

	private void givenTransactionWithMissingAutoRenewPeriod() {
		givenTransaction(
				ConsensusCreateTopicTransactionBody.newBuilder()
		);
	}

	private void givenTransactionWithInvalidAutoRenewAccountId() {
		givenTransaction(
				getBasicValidTransactionBodyBuilder()
						.setAutoRenewAccount(MISC_ACCOUNT)
		);
	}

	private void givenTransactionWithDetachedAutoRenewAccountId() {
		givenTransaction(
				getBasicValidTransactionBodyBuilder()
						.setAutoRenewAccount(MISC_ACCOUNT)
		);
	}

	private void givenTransactionWithAutoRenewAccountWithoutAdminKey() {
		givenTransaction(
				getBasicValidTransactionBodyBuilder()
						.setAutoRenewAccount(MISC_ACCOUNT)
		);
		
	}

	private TransactionID ourTxnId() {
		return TransactionID.newBuilder()
				.setAccountID(payer)
				.setTransactionValidStart(
						Timestamp.newBuilder().setSeconds(consensusTimestamp.getEpochSecond()))
				.build();
	}
}
