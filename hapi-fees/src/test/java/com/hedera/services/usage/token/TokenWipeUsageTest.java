package com.hedera.services.usage.token;

/*-
 * ‌
 * Hedera Services API Fees
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

import com.hedera.services.test.IdUtils;
import com.hedera.services.usage.EstimatorFactory;
import com.hedera.services.usage.SigUsage;
import com.hedera.services.usage.TxnUsageEstimator;
import com.hedera.services.usage.TxnUsage;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenWipeAccountTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.fee.FeeBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;

import static com.hedera.services.test.UsageUtils.A_USAGES_MATRIX;
import static com.hedera.services.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.services.usage.token.TokenEntitySizes.TOKEN_ENTITY_SIZES;
import static org.junit.Assert.assertEquals;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;
import static org.mockito.Mockito.times;

@RunWith(JUnitPlatform.class)
public class TokenWipeUsageTest {
	long now = 1_234_567L;
	int numSigs = 3, sigSize = 100, numPayerKeys = 1;
	SigUsage sigUsage = new SigUsage(numSigs, sigSize, numPayerKeys);
	TokenID id = IdUtils.asToken("0.0.75231");

	TransactionBody txn;
	TokenWipeAccountTransactionBody op;

	EstimatorFactory factory;
	TxnUsageEstimator base;
	TokenWipeUsage subject;

	@BeforeEach
	public void setUp() throws Exception {
		base = mock(TxnUsageEstimator.class);
		given(base.get()).willReturn(A_USAGES_MATRIX);

		factory = mock(EstimatorFactory.class);
		given(factory.get(any(), any(), any())).willReturn(base);

		TxnUsage.estimatorFactory = factory;
	}

	@Test
	public void createsExpectedDelta() {
		givenOp();
		// and:
		subject = TokenWipeUsage.newEstimate(txn, sigUsage);

		// when:
		var actual = subject.get();

		// then:
		assertEquals(A_USAGES_MATRIX, actual);
		// and:
		verify(base, times(2)).addBpt(FeeBuilder.BASIC_ENTITY_ID_SIZE);
		verify(base).addBpt(8);
		verify(base).addRbs(
				TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(1, 1) *
						USAGE_PROPERTIES.legacyReceiptStorageSecs());
	}

	private void givenOp() {
		op = TokenWipeAccountTransactionBody.newBuilder()
				.setToken(id)
				.build();
		setTxn();
	}

	private void setTxn() {
		txn = TransactionBody.newBuilder()
				.setTransactionID(TransactionID.newBuilder()
						.setTransactionValidStart(Timestamp.newBuilder()
								.setSeconds(now)))
				.setTokenWipe(op)
				.build();
	}
}
