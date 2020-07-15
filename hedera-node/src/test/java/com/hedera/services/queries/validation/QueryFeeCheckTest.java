package com.hedera.services.queries.validation;

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

import com.hedera.services.state.merkle.MerkleAccount;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.swirlds.fcmap.FCMap;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;

import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_ID_DOES_NOT_EXIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RECEIVING_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.*;

@RunWith(JUnitPlatform.class)
class QueryFeeCheckTest {
	AccountID aMissing = asAccount("1.2.3");
	AccountID aRich = asAccount("0.0.2");
	AccountID aNode = asAccount("0.0.3");
	AccountID aBroke = asAccount("0.0.13257");
	long aLittle = 2L, aLot = Long.MAX_VALUE - 1L;
	MerkleAccount broke, rich;
	MerkleEntityId missingKey = MerkleEntityId.fromPojoAccountId(aMissing);
	MerkleEntityId richKey = MerkleEntityId.fromPojoAccountId(aRich);
	MerkleEntityId brokeKey = MerkleEntityId.fromPojoAccountId(aBroke);
	MerkleEntityId nodeKey = MerkleEntityId.fromPojoAccountId(aNode);

	FCMap<MerkleEntityId, MerkleAccount> accounts;

	QueryFeeCheck subject;

	@BeforeEach
	private void setup() {
		broke = mock(MerkleAccount.class);
		given(broke.getBalance()).willReturn(aLittle);
		rich = mock(MerkleAccount.class);
		given(rich.getBalance()).willReturn(aLot);

		accounts = mock(FCMap.class);
		given(accounts.get(argThat(missingKey::equals))).willReturn(null);
		given(accounts.get(argThat(richKey::equals))).willReturn(rich);
		given(accounts.get(argThat(brokeKey::equals))).willReturn(broke);
		given(accounts.containsKey(argThat(missingKey::equals))).willReturn(false);
		given(accounts.containsKey(argThat(richKey::equals))).willReturn(true);
		given(accounts.containsKey(argThat(brokeKey::equals))).willReturn(true);
		given(accounts.containsKey(argThat(nodeKey::equals))).willReturn(true);

		subject = new QueryFeeCheck(accounts);
	}

	@Test
	public void rejectsEmptyTransfers() {
		// expect:
		assertEquals(INVALID_ACCOUNT_AMOUNTS, subject.transfersPlausibility(null));
		assertEquals(INVALID_ACCOUNT_AMOUNTS, subject.transfersPlausibility(Collections.emptyList()));
	}

	@Test
	public void acceptsSufficientFee() {
		// expect:
		assertEquals(
				OK,
				subject.nodePaymentValidity(
						transfersWith(
								adjustmentWith(aRich, -aLittle),
								adjustmentWith(aNode, aLittle)),
						aLittle - 1, aNode));
	}

	@Test
	public void rejectsWrongRecipient() {
		// expect:
		assertEquals(
				INVALID_RECEIVING_NODE_ACCOUNT,
				subject.nodePaymentValidity(
						transfersWith(
								adjustmentWith(aRich, -aLittle ),
								adjustmentWith(aBroke, aLittle)),
						aLittle - 1, aNode));
	}

	@Test
	public void rejectsMultipleRecipients() {
		// expect:
		assertEquals(
				INVALID_RECEIVING_NODE_ACCOUNT,
				subject.nodePaymentValidity(
						transfersWith(
								adjustmentWith(aRich, -aLittle * 2),
								adjustmentWith(aBroke, aLittle),
								adjustmentWith(aNode, aLittle)),
						aLittle - 1, aNode));
	}

	@Test
	public void rejectsInsufficientFee() {
		// expect:
		assertEquals(
				INSUFFICIENT_TX_FEE,
				subject.nodePaymentValidity(
						transfersWith(
								adjustmentWith(aRich, -aLittle),
								adjustmentWith(aBroke, aLittle)),
						aLittle + 1, aNode));
	}

	@Test
	public void filtersOnBasicImplausibility() {
		// expect:
		assertEquals(
				INVALID_ACCOUNT_AMOUNTS,
				subject.nodePaymentValidity(
						transfersWith(
								adjustmentWith(aRich, aLot),
								adjustmentWith(aBroke, aLittle)), 0L, aNode));
	}

	@Test
	public void rejectsOverflowingTransfer() {
		// expect:
		assertEquals(
				INVALID_ACCOUNT_AMOUNTS,
				subject.transfersPlausibility(
						transfersWith(
								adjustmentWith(aRich, aLot),
								adjustmentWith(aBroke, aLittle))));
	}

	@Test
	public void rejectsNonNetTransfer() {
		// expect:
		assertEquals(
				INVALID_ACCOUNT_AMOUNTS,
				subject.transfersPlausibility(
						transfersWith(
								adjustmentWith(aRich, aLittle),
								adjustmentWith(aBroke, aLittle))));
	}

	@Test
	public void catchesBadEntry() {
		// expect:
		assertEquals(
				ACCOUNT_ID_DOES_NOT_EXIST,
				subject.transfersPlausibility(
						transfersWith(
								adjustmentWith(aRich, -aLittle),
								adjustmentWith(aMissing, 0),
								adjustmentWith(aBroke, aLittle))));
	}

	@Test
	public void rejectsMinValue() {
		// given:
		var adjustment = adjustmentWith(aRich, Long.MIN_VALUE);

		// when:
		var status = subject.adjustmentPlausibility(adjustment);

		// then:
		assertEquals(INVALID_ACCOUNT_AMOUNTS, status);
	}

	@Test
	public void nonexistentSenderHasNoBalance() {
		// given:
		var adjustment = adjustmentWith(aMissing, -aLittle);

		// when:
		var status = subject.adjustmentPlausibility(adjustment);

		// then:
		assertEquals(INSUFFICIENT_PAYER_BALANCE, status);
	}

	@Test
	public void brokePayerRejected() {
		// given:
		var adjustment = adjustmentWith(aBroke, -aLot);

		// when:
		var status = subject.adjustmentPlausibility(adjustment);

		// then:
		assertEquals(INSUFFICIENT_PAYER_BALANCE, status);
	}

	@Test
	public void missingReceiverRejected() {
		// given:
		var adjustment = adjustmentWith(aMissing, aLot);

		// when:
		var status = subject.adjustmentPlausibility(adjustment);

		// then:
		assertEquals(ACCOUNT_ID_DOES_NOT_EXIST, status);
	}

	private AccountAmount adjustmentWith(AccountID id, long amount) {
		return AccountAmount.newBuilder()
				.setAccountID(id)
				.setAmount(amount)
				.build();
	}

	private List<AccountAmount> transfersWith(
			AccountAmount a,
			AccountAmount b
	) {
		return List.of(a, b);
	}

	private List<AccountAmount> transfersWith(
			AccountAmount a,
			AccountAmount b,
			AccountAmount c
	) {
		return List.of(a, b, c);
	}
}
