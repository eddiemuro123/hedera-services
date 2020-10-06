package com.hedera.services.fees.calculation.crypto.queries;

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
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.fees.calculation.UsageEstimatorUtils;
import com.hedera.services.fees.calculation.token.queries.GetTokenInfoResourceUsage;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.usage.crypto.CryptoGetInfoUsage;
import com.hedera.services.usage.token.TokenGetInfoUsage;
import com.hedera.services.utils.MiscUtils;
import com.hedera.test.factories.accounts.MerkleAccountFactory;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoGetInfoQuery;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.fee.CryptoFeeBuilder;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleAccount;
import com.swirlds.fcmap.FCMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.function.Function;

import static com.hedera.services.state.merkle.MerkleEntityId.fromAccountId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.*;
import static com.hedera.test.utils.IdUtils.*;
import static com.hederahashgraph.api.proto.java.ResponseType.*;

@RunWith(JUnitPlatform.class)
class GetAccountInfoResourceUsageTest {
	public static final FeeData MOCK_CRYPTO_GET_INFO_USAGE = UsageEstimatorUtils.defaultPartitioning(
			FeeComponents.newBuilder()
					.setMin(1)
					.setMax(1_000_000)
					.setConstant(1)
					.setBpt(1)
					.setVpt(1)
					.setRbh(1)
					.setSbh(1)
					.setGas(1)
					.setTv(1)
					.setBpr(1)
					.setSbpr(1)
					.build(), 1);
	StateView view;
	CryptoFeeBuilder usageEstimator;
	FCMap<MerkleEntityId, MerkleAccount> accounts;
	GetAccountInfoResourceUsage subject;
	Key aKey = Key.newBuilder().setEd25519(ByteString.copyFrom("NONSENSE".getBytes())).build();
	String a = "0.0.1234";
	MerkleAccount aValue;
	TokenID aToken = asToken("0.0.1001");
	TokenID bToken = asToken("0.0.1002");
	TokenID cToken = asToken("0.0.1003");
	String memo = "Hi there!";

	CryptoGetInfoUsage estimator;
	Function<Query, CryptoGetInfoUsage> factory;

	PropertySource propertySource;

	@BeforeEach
	private void setup() throws Throwable {
		aValue = MerkleAccountFactory.newAccount()
				.accountKeys(aKey)
				.tokens(aToken, bToken, cToken)
				.proxy(asAccount("0.0.4321"))
				.memo(memo)
				.get();
		usageEstimator = mock(CryptoFeeBuilder.class);
		accounts = mock(FCMap.class);
		propertySource = mock(PropertySource.class);
		view = new StateView(StateView.EMPTY_TOPICS_SUPPLIER, () -> accounts, propertySource);

		estimator = mock(CryptoGetInfoUsage.class);
		factory = mock(Function.class);
		given(factory.apply(any())).willReturn(estimator);

		GetAccountInfoResourceUsage.factory = factory;

		given(estimator.givenCurrentKey(aKey)).willReturn(estimator);
		given(estimator.givenCurrentlyUsingProxy()).willReturn(estimator);
		given(estimator.givenCurrentMemo(any())).willReturn(estimator);
		given(estimator.givenCurrentTokenAssocs(3)).willReturn(estimator);
		given(estimator.get()).willReturn(MOCK_CRYPTO_GET_INFO_USAGE);

		subject = new GetAccountInfoResourceUsage();
	}

	@Test
	public void usesEstimator() {
		given(accounts.containsKey(fromAccountId(asAccount(a)))).willReturn(true);
		given(accounts.get(fromAccountId(asAccount(a)))).willReturn(aValue);

		// when:
		var usage = subject.usageGiven(accountInfoQuery(a, ANSWER_ONLY), view);

		// then:
		assertEquals(MOCK_CRYPTO_GET_INFO_USAGE, usage);
		// and:
		verify(estimator).givenCurrentlyUsingProxy();
	}

	@Test
	public void returnsDefaultIfNoSuchAccount() {
		given(accounts.containsKey(fromAccountId(asAccount(a)))).willReturn(false);

		// when:
		var usage = subject.usageGiven(accountInfoQuery(a, ANSWER_ONLY), view);

		// then:
		assertSame(FeeData.getDefaultInstance(), usage);
	}


	@Test
	public void throwsIaeWhenAccountIsntKosher() {
		// given:
		Query query = accountInfoQuery(a, ANSWER_ONLY);
		// and:
		given(accounts.containsKey(any())).willThrow(IllegalStateException.class);

		// expect:
		assertThrows(IllegalArgumentException.class, () -> subject.usageGiven(query, view));
	}

	@Test
	public void recognizesApplicableQuery() {
		// given:
		Query accountInfoQuery = accountInfoQuery(a, COST_ANSWER);
		Query nonAccountInfoQuery = nonAccountInfoQuery();

		// expect:
		assertTrue(subject.applicableTo(accountInfoQuery));
		assertFalse(subject.applicableTo(nonAccountInfoQuery));
	}

	private Query accountInfoQuery(String target, ResponseType type) {
		AccountID id = asAccount(target);
		CryptoGetInfoQuery.Builder op = CryptoGetInfoQuery.newBuilder()
				.setAccountID(id)
				.setHeader(QueryHeader.newBuilder().setResponseType(type));
		return Query.newBuilder()
				.setCryptoGetInfo(op)
				.build();
	}

	private Query nonAccountInfoQuery() {
		return Query.newBuilder().build();
	}
}
