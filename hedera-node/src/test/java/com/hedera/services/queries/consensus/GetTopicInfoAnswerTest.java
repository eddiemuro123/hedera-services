package com.hedera.services.queries.consensus;

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

import com.google.protobuf.ByteString;
import com.hedera.services.context.domain.topic.Topic;
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.test.factories.topics.TopicFactory;
import com.hederahashgraph.api.proto.java.ConsensusGetTopicInfoQuery;
import com.hederahashgraph.api.proto.java.ConsensusGetTopicInfoResponse;
import com.hederahashgraph.api.proto.java.ConsensusTopicInfo;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseHeader;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.TopicID;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hedera.services.legacy.core.MapKey;
import com.swirlds.fcmap.FCMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;

import static com.hedera.test.factories.scenarios.TxnHandlingScenario.COMPLEX_KEY_ACCOUNT_KT;
import static com.hedera.test.utils.TxnUtils.payerSponsoredTransfer;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PLATFORM_NOT_ACTIVE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOPIC_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_ONLY;
import static com.hederahashgraph.api.proto.java.ResponseType.COST_ANSWER;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;
import static com.hedera.services.legacy.core.MapKey.getMapKey;
import static com.hedera.test.utils.IdUtils.*;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.MISC_ACCOUNT_KT;

@RunWith(JUnitPlatform.class)
class GetTopicInfoAnswerTest {
	long seqNo = 1_234L;
	FCMap topics;
	byte[] hash = "NOT A HASH".getBytes();
	StateView view;
	OptionValidator optionValidator;
	Key adminKey, submitKey;
	long fee = 1_234L;
	String id = "1.2.3";
	String node = "0.0.3";
	String payer = "0.0.12345";
	String target = "3.2.1";
	String memo = "This was Mr. Bleaney's room...";
	String idLit = "0.0.12345";
	long expiry = 1_234_567L;
	long duration = 55L;
	Topic topic;
	private Transaction paymentTxn;

	GetTopicInfoAnswer subject;

	@BeforeEach
	private void setup() throws Exception {
		adminKey = COMPLEX_KEY_ACCOUNT_KT.asKey();
		submitKey = MISC_ACCOUNT_KT.asKey();
		topics = mock(FCMap.class);
		topic = TopicFactory.newTopic()
				.adminKey(adminKey)
				.submitKey(submitKey)
				.memo(memo)
				.expiry(expiry)
				.deleted(false)
				.autoRenewDuration(duration)
				.autoRenewId(asAccount(id))
				.get();
		topic.setRunningHash(hash);
		topic.setSequenceNumber(seqNo);
		MapKey key = getMapKey(asTopic(target));
		given(topics.get(key)).willReturn(topic);
		view = new StateView(topics, StateView.EMPTY_ACCOUNTS);
		optionValidator = mock(OptionValidator.class);

		subject = new GetTopicInfoAnswer(optionValidator);
	}

	@Test
	public void syntaxCheckRequiresId() {
		// given:
		ConsensusGetTopicInfoQuery op = ConsensusGetTopicInfoQuery.newBuilder().build();
		Query query = Query.newBuilder().setConsensusGetTopicInfo(op).build();

		// when:
		ResponseCodeEnum status = subject.checkValidity(query, view);

		// expect:
		assertEquals(INVALID_TOPIC_ID, status);
	}

	@Test
	public void requiresOkMetaValidity() {
		// setup:
		TopicID id = asTopic(idLit);

		// given:
		ConsensusGetTopicInfoQuery op = ConsensusGetTopicInfoQuery.newBuilder()
				.setTopicID(id)
				.build();
		Query query = Query.newBuilder().setConsensusGetTopicInfo(op).build();

		// when:
		Response response = subject.responseGiven(query, view, PLATFORM_NOT_ACTIVE);
		ResponseCodeEnum status = response.getConsensusGetTopicInfo()
				.getHeader()
				.getNodeTransactionPrecheckCode();

		// expect:
		assertEquals(PLATFORM_NOT_ACTIVE, status);
		assertEquals(id, response.getConsensusGetTopicInfo().getTopicID());
	}

	@Test
	public void syntaxCheckValidatesTidIfPresent() {
		// setup:
		TopicID tid = asTopic(idLit);

		// given:
		ConsensusGetTopicInfoQuery op = ConsensusGetTopicInfoQuery.newBuilder()
				.setTopicID(tid)
				.build();
		Query query = Query.newBuilder().setConsensusGetTopicInfo(op).build();
		// and:
		given(optionValidator.queryableTopicStatus(tid, topics)).willReturn(TOPIC_EXPIRED);

		// when:
		ResponseCodeEnum status = subject.checkValidity(query, view);

		// expect:
		assertEquals(TOPIC_EXPIRED, status);
	}

	@Test
	public void getsCostAnswerResponse() throws Throwable {
		// setup:
		Query query = validQuery(COST_ANSWER, fee, target);

		// when:
		Response response = subject.responseGiven(query, view, OK, fee);

		// then:
		assertTrue(response.hasConsensusGetTopicInfo());
		assertEquals(OK, response.getConsensusGetTopicInfo().getHeader().getNodeTransactionPrecheckCode());
		assertEquals(COST_ANSWER, response.getConsensusGetTopicInfo().getHeader().getResponseType());
		assertEquals(fee, response.getConsensusGetTopicInfo().getHeader().getCost());
	}

	@Test
	public void getsValidity() {
		// given:
		Response response = Response.newBuilder()
				.setConsensusGetTopicInfo(
						ConsensusGetTopicInfoResponse.newBuilder()
								.setHeader(ResponseHeader.newBuilder()
										.setNodeTransactionPrecheckCode(TOPIC_EXPIRED))).build();

		// expect:
		assertEquals(TOPIC_EXPIRED, subject.extractValidityFrom(response));
	}


	@Test
	public void recognizesFunction() {
		// expect:
		assertEquals(HederaFunctionality.ConsensusGetTopicInfo, subject.canonicalFunction());
	}

	@Test
	public void requiresAnswerOnlyCostAsExpected() throws Throwable {
		// expect:
		assertTrue(subject.needsAnswerOnlyCost(validQuery(COST_ANSWER, 0, target)));
		assertFalse(subject.needsAnswerOnlyCost(validQuery(ANSWER_ONLY, 0, target)));
	}

	@Test
	public void requiresAnswerOnlyPayment() throws Throwable {
		// expect:
		assertFalse(subject.requiresNodePayment(validQuery(COST_ANSWER, 0, target)));
		assertTrue(subject.requiresNodePayment(validQuery(ANSWER_ONLY, 0, target)));
	}

	@Test
	public void getsExpectedPayment() throws Throwable {
		// given:
		Query query = validQuery(COST_ANSWER, fee, target);

		// expect:
		assertEquals(paymentTxn, subject.extractPaymentFrom(query).get().getSignedTxn());
	}

	@Test
	public void getsTheTopicInfo() throws Throwable {
		// setup:
		Query query = validQuery(ANSWER_ONLY, fee, target);

		// when:
		Response response = subject.responseGiven(query, view, OK, fee);

		// then:
		assertTrue(response.hasConsensusGetTopicInfo());
		assertEquals(OK, response.getConsensusGetTopicInfo().getHeader().getNodeTransactionPrecheckCode());
		assertEquals(ANSWER_ONLY, response.getConsensusGetTopicInfo().getHeader().getResponseType());
		assertEquals(0, response.getConsensusGetTopicInfo().getHeader().getCost());
		assertEquals(asTopic(target), response.getConsensusGetTopicInfo().getTopicID());
		// and:
		ConsensusTopicInfo info = response.getConsensusGetTopicInfo().getTopicInfo();
		assertEquals(adminKey, info.getAdminKey());
		assertEquals(submitKey, info.getSubmitKey());
		assertEquals(topic.getExpirationTimestamp().getSeconds(), info.getExpirationTime().getSeconds());
		assertEquals(topic.getAutoRenewDurationSeconds(), info.getAutoRenewPeriod().getSeconds());
		assertEquals(ByteString.copyFrom(topic.getRunningHash()), info.getRunningHash());
		assertEquals(topic.getAutoRenewAccountId().getAccountNum(), info.getAutoRenewAccount().getAccountNum());
		assertEquals(topic.getSequenceNumber(), info.getSequenceNumber());
		assertEquals(topic.getMemo(), info.getMemo());
	}

	@Test
	public void getsTopicInfoWithEmptyRunningHash() throws Throwable {
		// setup:
		Query query = validQuery(ANSWER_ONLY, fee, target);
		topic.setRunningHash(null);
		given(topics.get(asTopic(target))).willReturn(topic);

		// when:
		Response response = subject.responseGiven(query, view, OK, fee);

		// then:
		assertTrue(response.hasConsensusGetTopicInfo());
		// and:
		ConsensusTopicInfo info = response.getConsensusGetTopicInfo().getTopicInfo();
		assertArrayEquals(new byte[48], info.getRunningHash().toByteArray());
	}

	private Query validQuery(ResponseType type, long payment, String idLit) throws Throwable {
		this.paymentTxn = payerSponsoredTransfer(payer, COMPLEX_KEY_ACCOUNT_KT, node, payment);
		QueryHeader.Builder header = QueryHeader.newBuilder()
				.setPayment(this.paymentTxn)
				.setResponseType(type);
		ConsensusGetTopicInfoQuery.Builder op = ConsensusGetTopicInfoQuery.newBuilder()
				.setHeader(header)
				.setTopicID(asTopic(idLit));
		return Query.newBuilder().setConsensusGetTopicInfo(op).build();
	}
}
