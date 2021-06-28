package com.hedera.services.usage.token;

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

import com.hedera.services.test.IdUtils;
import com.hedera.services.usage.EstimatorFactory;
import com.hedera.services.usage.SigUsage;
import com.hedera.services.usage.TxnUsage;
import com.hedera.services.usage.TxnUsageEstimator;
import com.hederahashgraph.api.proto.java.*;
import com.hederahashgraph.fee.FeeBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.hedera.services.test.UsageUtils.A_NON_FUNGIBLE_UNIQUE_USAGES_MATRIX;
import static com.hedera.services.test.UsageUtils.A_USAGES_MATRIX;
import static com.hedera.services.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.services.usage.token.entities.TokenEntitySizes.TOKEN_ENTITY_SIZES;
import static org.junit.Assert.assertEquals;
import static org.mockito.BDDMockito.*;

public class TokenBurnUsageTest {
	long now = 1_234_567L;
	int numSigs = 3, sigSize = 100, numPayerKeys = 1;
	SigUsage sigUsage = new SigUsage(numSigs, sigSize, numPayerKeys);
	TokenID id = IdUtils.asToken("0.0.75231");
	List<Long> serialNumbersList = new ArrayList<>();

	TokenBurnTransactionBody op;
	TransactionBody txn;

	EstimatorFactory factory;
	TxnUsageEstimator base;
	TokenBurnUsage subject;

	@BeforeEach
	public void setUp() throws Exception {
		base = mock(TxnUsageEstimator.class);
		given(base.get()).willReturn(A_USAGES_MATRIX);

		factory = mock(EstimatorFactory.class);
		given(factory.get(any(), any(), any())).willReturn(base);

		TxnUsage.estimatorFactory = factory;

		serialNumbersList.add(1L);
	}

	@Test
	public void createsExpectedDelta() {
		givenOp();
		// and:
		subject = TokenBurnUsage.newEstimate(txn, sigUsage);
		subject.givenSubType(SubType.TOKEN_FUNGIBLE_COMMON);
		given(base.get(SubType.TOKEN_FUNGIBLE_COMMON)).willReturn(A_USAGES_MATRIX);
		// when:2
		var actual = subject.get();

		// then:
		assertEquals(A_USAGES_MATRIX, actual);
		// and:
		verify(base).addBpt(8);
		verify(base).addBpt(FeeBuilder.BASIC_ENTITY_ID_SIZE);
		verify(base).addRbs(
				TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(1, 1, 0) *
						USAGE_PROPERTIES.legacyReceiptStorageSecs());
	}

	@Test
	public void createsExpectedDeltaWithNonFungibleUnique() {
		givenOp();
		// and:
		given(base.get(SubType.TOKEN_NON_FUNGIBLE_UNIQUE)).willReturn(A_NON_FUNGIBLE_UNIQUE_USAGES_MATRIX);
		subject = TokenBurnUsage.newEstimate(txn, sigUsage);
		subject.givenSubType(SubType.TOKEN_NON_FUNGIBLE_UNIQUE);

		// when:
		var actual = subject.get();

		// then:
		assertEquals(A_NON_FUNGIBLE_UNIQUE_USAGES_MATRIX, actual);
		// and:
		verify(base).addBpt(1);
		verify(base).addRbs(1);
		verify(base).addRbs(TOKEN_ENTITY_SIZES.bytesUsedForUniqueTokenTransfers(1));
		verify(base).addRbs(
				TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(1, 0, 1) *
						USAGE_PROPERTIES.legacyReceiptStorageSecs());
	}

	private void givenOp() {
		op = TokenBurnTransactionBody.newBuilder()
				.setToken(id)
				.addAllSerialNumbers(serialNumbersList)
				.build();
		setTxn();
	}

	private void setTxn() {
		txn = TransactionBody.newBuilder()
				.setTransactionID(TransactionID.newBuilder()
						.setTransactionValidStart(Timestamp.newBuilder()
								.setSeconds(now)))
				.setTokenBurn(op)
				.build();
	}
}
