package com.hedera.services.state.merkle;

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

import com.hedera.services.legacy.core.jproto.JEd25519Key;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.state.serdes.DomainSerdes;
import com.hedera.services.state.serdes.IoReadingFunction;
import com.hedera.services.state.serdes.IoWritingConsumer;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.utils.MiscUtils;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;
import org.mockito.InOrder;

import java.io.IOException;
import java.util.Arrays;

import static com.hedera.services.state.merkle.MerkleAccountState.FREEZE_MASK;
import static com.hedera.services.state.merkle.MerkleAccountState.MAX_CONCEIVABLE_TOKEN_BALANCES_SIZE;
import static com.hedera.services.state.merkle.MerkleAccountState.NO_TOKEN_BALANCES;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.TOKEN_ADMIN_KT;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.TOKEN_FREEZE_KT;
import static com.hedera.test.utils.IdUtils.tokenWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SETTING_NEGATIVE_ACCOUNT_BALANCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.anyInt;
import static org.mockito.BDDMockito.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.times;
import static org.mockito.Mockito.inOrder;

@RunWith(JUnitPlatform.class)
class MerkleAccountStateTest {
	JKey key;
	long expiry = 1_234_567L;
	long balance = 555_555L;
	long autoRenewSecs = 234_567L;
	long senderThreshold = 1_234L;
	long receiverThreshold = 4_321L;
	String memo = "A memo";
	boolean deleted = true;
	boolean smartContract = true;
	boolean receiverSigRequired = true;
	EntityId proxy;
	long firstToken = 555, secondToken = 666, thirdToken = 777;
	long firstBalance = 123, secondBalance = 234, thirdBalance = 345;
	long firstFlag = 0, secondFlag = 0, thirdFlag = 0 | MerkleAccountState.FREEZE_MASK;
	long[] tokenRels = new long[] {
		firstToken, firstBalance, firstFlag,
			secondToken, secondBalance, secondFlag,
			thirdToken, thirdBalance, thirdFlag
	};

	JKey otherKey;
	long otherExpiry = 7_234_567L;
	long otherBalance = 666_666L;
	long otherAutoRenewSecs = 432_765L;
	long otherSenderThreshold = 4_321L;
	long otherReceiverThreshold = 1_234L;
	String otherMemo = "Another memo";
	boolean otherDeleted = false;
	boolean otherSmartContract = false;
	boolean otherReceiverSigRequired = false;
	EntityId otherProxy;
	long otherFirstBalance = 321, otherSecondBalance = 432, otherThirdBalance = 543;
	long otherFirstFlag = 0, otherSecondFlag = 0, otherThirdFlag = 0;
	long[] otherTokenRels = new long[] {
			firstToken, otherFirstBalance, otherFirstFlag,
			secondToken, otherSecondBalance, otherSecondFlag,
			thirdToken, otherThirdBalance, otherThirdFlag
	};
	JKey adminKey = TOKEN_ADMIN_KT.asJKeyUnchecked();
	JKey optionalFreezeKey = TOKEN_FREEZE_KT.asJKeyUnchecked();

	MerkleToken unfrozenToken = new MerkleToken(
			100, 1,
			adminKey,
			"UnfrozenToken", false,
			new EntityId(1, 2, 3));
	MerkleToken frozenToken = new MerkleToken(
			100, 1,
			adminKey,
			"FrozenToken", true,
			new EntityId(1, 2, 4));
	MerkleToken freezeableToken = new MerkleToken(
			100, 1,
			adminKey,
			"FrozenToken", false,
			new EntityId(1, 2, 4));

	DomainSerdes serdes;

	MerkleAccountState subject;
	MerkleAccountState release070Subject;
	MerkleAccountState otherSubject;

	@BeforeEach
	public void setup() {
		frozenToken.setFreezeKey(optionalFreezeKey);
		freezeableToken.setFreezeKey(optionalFreezeKey);

		key = new JEd25519Key("abcdefghijklmnopqrstuvwxyz012345".getBytes());
		proxy = new EntityId(1L, 2L, 3L);
		// and:
		otherKey = new JEd25519Key("aBcDeFgHiJkLmNoPqRsTuVwXyZ012345".getBytes());
		otherProxy = new EntityId(3L, 2L, 1L);

		release070Subject = new MerkleAccountState(
				key,
				expiry, balance, autoRenewSecs, senderThreshold, receiverThreshold,
				memo,
				deleted, smartContract, receiverSigRequired,
				proxy,
				NO_TOKEN_BALANCES);
		subject = new MerkleAccountState(
				key,
				expiry, balance, autoRenewSecs, senderThreshold, receiverThreshold,
				memo,
				deleted, smartContract, receiverSigRequired,
				proxy,
				tokenRels);

		serdes = mock(DomainSerdes.class);
		MerkleAccountState.serdes = serdes;
	}

	@AfterEach
	public void cleanup() {
		MerkleAccountState.serdes = new DomainSerdes();
	}

	@Test
	public void rejectsMisalignedRelationships() {
		// expect:
		assertThrows(IllegalArgumentException.class, () ->
				new MerkleAccountState(
						key,
						expiry, balance, autoRenewSecs, senderThreshold, receiverThreshold,
						memo,
						deleted, smartContract, receiverSigRequired,
						proxy,
						new long[] { 1L, 2L }));
	}

	@Test
	public void understandsNum() {
		// expect:
		assertEquals(3, subject.numTokenRelationships());
	}

	@Test
	public void getsTokenBalanceIfPresent()	 {
		// expect:
		assertEquals(firstBalance, subject.getTokenBalance(tokenWith(firstToken)));
		assertEquals(secondBalance, subject.getTokenBalance(tokenWith(secondToken)));
		assertEquals(thirdBalance, subject.getTokenBalance(tokenWith(thirdToken)));
	}

	@Test
	public void willNotSetNewBalanceIfTokenFreezesByDefault() {
		// given:
		var result = subject.validityOfSettingTokenBalance(
				tokenWith(firstToken - 1), frozenToken, firstBalance + 1);

		// expect:
		assertEquals(ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN, result);

		// and:
		assertThrows(
				IllegalStateException.class,
				() -> subject.setTokenBalance(tokenWith(firstToken - 1), frozenToken, firstBalance + 1));
		assertEquals(0, subject.getTokenBalance(tokenWith(firstToken - 1)));
	}

	@Test
	public void freezesAsRequested() {
		// when:
		subject.freeze(tokenWith(firstToken), frozenToken);

		// expect:
		assertEquals(FREEZE_MASK, tokenRels[2] & FREEZE_MASK);
	}

	@Test
	public void unfreezesAsRequested() {
		// when:
		subject.unfreeze(tokenWith(thirdToken), frozenToken);

		// expect:
		assertEquals(0, tokenRels[8] & FREEZE_MASK);
	}

	@Test
	public void freezeIsNoopIfTokenCannotFreeze() {
		// when:
		subject.freeze(tokenWith(firstToken), unfrozenToken);

		// expect:
		assertEquals(0, tokenRels[2] & FREEZE_MASK);
	}

	@Test
	public void unfreezeIsNoopIfTokenCannotFreeze() {
		// when:
		subject.unfreeze(tokenWith(thirdToken), unfrozenToken);

		// expect:
		assertEquals(FREEZE_MASK, tokenRels[8]);
	}

	@Test
	public void freezeIsNoopIfNoExistingRelationshipAndTokenFreezesByDefault() {
		// given:
		var oldTokenRels = Arrays.copyOf(subject.tokenRels, 9);

		// when:
		subject.freeze(tokenWith(firstToken - 1), frozenToken);

		// expect:
		assertArrayEquals(oldTokenRels, subject.tokenRels);
	}

	@Test
	public void unfreezeCreatesRelationshipIfTokenFreezesByDefault() {
		// when:
		subject.unfreeze(tokenWith(firstToken - 1), frozenToken);

		// expect:
		assertEquals(12, subject.tokenRels.length);
		assertEquals(firstToken - 1, subject.tokenRels[0]);
		assertEquals(0, subject.tokenRels[2] & FREEZE_MASK);
	}

	@Test
	public void unfreezeIsNoopRelationshipIfTokenDoesntFreezeByDefault() {
		// given:
		var oldTokenRels = Arrays.copyOf(subject.tokenRels, 9);

		// when:
		subject.unfreeze(tokenWith(firstToken - 1), freezeableToken);

		// expect:
		assertArrayEquals(oldTokenRels, subject.tokenRels);
	}

	@Test
	public void freezeCreatesRelationshipIfTokenDoesntFreezeByDefault() {
		// when:
		subject.freeze(tokenWith(firstToken - 1), freezeableToken);

		// expect:
		assertEquals(12, subject.tokenRels.length);
		assertEquals(firstToken - 1, subject.tokenRels[0]);
		assertEquals(FREEZE_MASK, subject.tokenRels[2]);
	}

	@Test
	public void recognizesFreezeStatus() {
		// expect:
		assertTrue(subject.isFrozen(tokenWith(thirdToken), frozenToken));
		assertTrue(subject.isFrozen(tokenWith(thirdToken - 1), frozenToken));
		// and:
		assertFalse(subject.isFrozen(tokenWith(secondToken), frozenToken));
		assertFalse(subject.isFrozen(tokenWith(thirdToken), unfrozenToken));
		assertFalse(subject.isFrozen(tokenWith(thirdToken - 1), freezeableToken));
	}

	@Test
	public void willNotSetBalanceIfTokenFrozen() {
		// given:
		var result = subject.validityOfSettingTokenBalance(
				tokenWith(thirdToken), frozenToken, 0);

		// expect:
		assertEquals(thirdBalance, subject.getTokenBalance(tokenWith(thirdToken)));
		// and:
		assertEquals(ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN, result);
		// and:
		assertThrows(
				IllegalStateException.class,
				() -> subject.setTokenBalance(tokenWith(thirdToken), frozenToken, 0));
	}

	@Test
	public void updatesUnfrozenTokenBalanceIfPresent()	 {
		// given:
		assertEquals(OK, subject.validityOfSettingTokenBalance(
				tokenWith(firstToken), unfrozenToken, firstBalance + 1));

		// when:
		subject.setTokenBalance(tokenWith(firstToken), unfrozenToken, firstBalance + 1);

		// expect:
		assertEquals(firstBalance + 1, subject.getTokenBalance(tokenWith(firstToken)));
	}

	@Test
	public void createsFirstUnfrozenTokenIfMissing() {
		// given:
		subject.setTokenBalance(tokenWith(firstToken - 1), unfrozenToken, firstBalance + 1);

		// expect:
		assertEquals(firstBalance + 1, subject.getTokenBalance(tokenWith(firstToken - 1)));
	}

	@Test
	public void createsSecondUnfrozenTokenIfMissing() {
		// given:
		subject.setTokenBalance(tokenWith(secondToken - 1), unfrozenToken, secondBalance + 1);

		// expect:
		assertEquals(secondBalance + 1, subject.getTokenBalance(tokenWith(secondToken - 1)));
	}

	@Test
	public void createsThirdUnfrozenTokenIfMissing() {
		// given:
		subject.setTokenBalance(tokenWith(thirdToken - 1), unfrozenToken, thirdBalance + 1);

		// expect:
		assertEquals(thirdBalance + 1, subject.getTokenBalance(tokenWith(thirdToken - 1)));
	}

	@Test
	public void createsFourthUnfrozenTokenIfMissing() {
		// given:
		subject.setTokenBalance(tokenWith(thirdToken + 1), unfrozenToken, thirdBalance + 2);

		// expect:
		assertEquals(thirdBalance + 2, subject.getTokenBalance(tokenWith(thirdToken + 1)));
	}

	@Test
	public void returnsZeroBalanceIfTokenNotRelated() {
		// expect:
		assertEquals(0, subject.getTokenBalance(TokenID.getDefaultInstance()));
	}

	@Test
	public void refusesToSetNegativeBalance() {
		// expect:
		assertEquals(
				SETTING_NEGATIVE_ACCOUNT_BALANCE,
				subject.validityOfSettingTokenBalance(tokenWith(firstToken), unfrozenToken, -1));
		// and:
		assertThrows(
				IllegalArgumentException.class,
				() -> subject.setTokenBalance(tokenWith(firstToken), unfrozenToken, -1));
	}

	@Test
	public void getsLogicalInsertIndexIfMissing()	 {
		// expect:
		assertEquals(-1, subject.logicalIndexOf(tokenWith(firstToken - 1)));
		assertEquals(-2, subject.logicalIndexOf(tokenWith(secondToken - 1)));
		assertEquals(-3, subject.logicalIndexOf(tokenWith(thirdToken - 1)));
		assertEquals(-4, subject.logicalIndexOf(tokenWith(thirdToken + 1)));
	}

	@Test
	public void toStringWorks() {
		// expect:
		assertEquals("MerkleAccountState{" +
						"key=" + MiscUtils.describe(key) + ", " +
						"expiry=" + expiry + ", " +
						"balance=" + balance + ", " +
						"autoRenewSecs=" + autoRenewSecs + ", " +
						"senderThreshold=" + senderThreshold + ", " +
						"receiverThreshold=" + receiverThreshold + ", " +
						"memo=" + memo + ", " +
						"deleted=" + deleted + ", " +
						"smartContract=" + smartContract + ", " +
						"receiverSigRequired=" + receiverSigRequired + ", " +
						"proxy=" + proxy + ", " +
						"tokenRels=[555, 123, " + firstFlag + ", " +
							"666, 234, " + secondFlag + ", " +
							"777, 345, " + thirdFlag + "]" + "}",
				subject.toString());
	}

	@Test
	public void release070DeserializeWorks() throws IOException {
		// setup:
		var in = mock(SerializableDataInputStream.class);
		// and:
		var newSubject = new MerkleAccountState();

		given(serdes.readNullable(argThat(in::equals), any(IoReadingFunction.class))).willReturn(key);
		given(in.readLong())
				.willReturn(expiry)
				.willReturn(balance)
				.willReturn(autoRenewSecs)
				.willReturn(senderThreshold)
				.willReturn(receiverThreshold);
		given(in.readLongArray(MAX_CONCEIVABLE_TOKEN_BALANCES_SIZE))
				.willThrow(IllegalStateException.class);
		given(in.readNormalisedString(anyInt())).willReturn(memo);
		given(in.readBoolean())
				.willReturn(deleted)
				.willReturn(smartContract)
				.willReturn(receiverSigRequired);
		given(serdes.readNullableSerializable(in)).willReturn(proxy);

		// when:
		newSubject.deserialize(in, MerkleAccountState.RELEASE_070_VERSION);

		// then:
		assertEquals(release070Subject, newSubject);
	}

	@Test
	public void release090DeserializeWorks() throws IOException {
		// setup:
		var in = mock(SerializableDataInputStream.class);
		// and:
		var newSubject = new MerkleAccountState();

		given(serdes.readNullable(argThat(in::equals), any(IoReadingFunction.class))).willReturn(key);
		given(in.readLong())
				.willReturn(expiry)
				.willReturn(balance)
				.willReturn(autoRenewSecs)
				.willReturn(senderThreshold)
				.willReturn(receiverThreshold);
		given(in.readLongArray(MAX_CONCEIVABLE_TOKEN_BALANCES_SIZE))
				.willReturn(tokenRels);
		given(in.readNormalisedString(anyInt())).willReturn(memo);
		given(in.readBoolean())
				.willReturn(deleted)
				.willReturn(smartContract)
				.willReturn(receiverSigRequired);
		given(serdes.readNullableSerializable(in)).willReturn(proxy);

		// when:
		newSubject.deserialize(in, MerkleAccountState.RELEASE_080_VERSION);

		// then:
		assertEquals(subject, newSubject);
	}

	@Test
	public void serializeWorks() throws IOException {
		// setup:
		var out = mock(SerializableDataOutputStream.class);
		// and:
		InOrder inOrder = inOrder(serdes, out);

		// when:
		subject.serialize(out);

		// then:
		inOrder.verify(serdes).writeNullable(argThat(key::equals), argThat(out::equals), any(IoWritingConsumer.class));
		inOrder.verify(out).writeLong(expiry);
		inOrder.verify(out).writeLong(balance);
		inOrder.verify(out).writeLong(autoRenewSecs);
		inOrder.verify(out).writeLong(senderThreshold);
		inOrder.verify(out).writeLong(receiverThreshold);
		inOrder.verify(out).writeNormalisedString(memo);
		inOrder.verify(out, times(3)).writeBoolean(true);
		inOrder.verify(serdes).writeNullableSerializable(proxy, out);
		inOrder.verify(out).writeLongArray(tokenRels);
	}

	@Test
	public void copyWorks() {
		// given:
		var copySubject = subject.copy();

		// expect:
		assertNotSame(copySubject, subject);
		assertNotSame(subject.tokenRels, copySubject.tokenRels);
		assertEquals(subject, copySubject);
	}

	@Test
	public void equalsWorksWithRadicalDifferences() {
		// expect:
		assertEquals(subject, subject);
		assertNotEquals(subject, null);
		assertNotEquals(subject, new Object());
	}

	@Test
	public void equalsWorksForKey() {
		// given:
		otherSubject = new MerkleAccountState(
				otherKey,
				expiry, balance, autoRenewSecs, senderThreshold, receiverThreshold,
				memo,
				deleted, smartContract, receiverSigRequired,
				proxy,
				tokenRels);

		// expect:
		assertNotEquals(subject, otherSubject);
	}

	@Test
	public void equalsWorksForExpiry() {
		// given:
		otherSubject = new MerkleAccountState(
				key,
				otherExpiry, balance, autoRenewSecs, senderThreshold, receiverThreshold,
				memo,
				deleted, smartContract, receiverSigRequired,
				proxy,
				tokenRels);

		// expect:
		assertNotEquals(subject, otherSubject);
	}

	@Test
	public void equalsWorksForBalance() {
		// given:
		otherSubject = new MerkleAccountState(
				key,
				expiry, otherBalance, autoRenewSecs, senderThreshold, receiverThreshold,
				memo,
				deleted, smartContract, receiverSigRequired,
				proxy,
				tokenRels);

		// expect:
		assertNotEquals(subject, otherSubject);
	}

	@Test
	public void equalsWorksForAutoRenewSecs() {
		// given:
		otherSubject = new MerkleAccountState(
				key,
				expiry, balance, otherAutoRenewSecs, senderThreshold, receiverThreshold,
				memo,
				deleted, smartContract, receiverSigRequired,
				proxy,
				tokenRels);

		// expect:
		assertNotEquals(subject, otherSubject);
	}

	@Test
	public void equalsWorksForSenderThreshold() {
		// given:
		otherSubject = new MerkleAccountState(
				key,
				expiry, balance, autoRenewSecs, otherSenderThreshold, receiverThreshold,
				memo,
				deleted, smartContract, receiverSigRequired,
				proxy,
				tokenRels);

		// expect:
		assertNotEquals(subject, otherSubject);
	}

	@Test
	public void equalsWorksForReceiverThreshold() {
		// given:
		otherSubject = new MerkleAccountState(
				key,
				expiry, balance, autoRenewSecs, senderThreshold, otherReceiverThreshold,
				memo,
				deleted, smartContract, receiverSigRequired,
				proxy,
				tokenRels);

		// expect:
		assertNotEquals(subject, otherSubject);
	}

	@Test
	public void equalsWorksForTokenBalances() {
		// given:
		otherSubject = new MerkleAccountState(
				key,
				expiry, balance, autoRenewSecs, senderThreshold, receiverThreshold,
				memo,
				deleted, smartContract, receiverSigRequired,
				proxy,
				otherTokenRels);

		// expect:
		assertNotEquals(subject, otherSubject);
	}

	@Test
	public void equalsWorksForMemo() {
		// given:
		otherSubject = new MerkleAccountState(
				key,
				expiry, balance, autoRenewSecs, senderThreshold, receiverThreshold,
				otherMemo,
				deleted, smartContract, receiverSigRequired,
				proxy,
				tokenRels);

		// expect:
		assertNotEquals(subject, otherSubject);
	}

	@Test
	public void equalsWorksForDeleted() {
		// given:
		otherSubject = new MerkleAccountState(
				key,
				expiry, balance, autoRenewSecs, senderThreshold, receiverThreshold,
				memo,
				otherDeleted, smartContract, receiverSigRequired,
				proxy,
				tokenRels);

		// expect:
		assertNotEquals(subject, otherSubject);
	}

	@Test
	public void equalsWorksForSmartContract() {
		// given:
		otherSubject = new MerkleAccountState(
				key,
				expiry, balance, autoRenewSecs, senderThreshold, receiverThreshold,
				memo,
				deleted, otherSmartContract, receiverSigRequired,
				proxy,
				tokenRels);

		// expect:
		assertNotEquals(subject, otherSubject);
	}

	@Test
	public void equalsWorksForReceiverSigRequired() {
		// given:
		otherSubject = new MerkleAccountState(
				key,
				expiry, balance, autoRenewSecs, senderThreshold, receiverThreshold,
				memo,
				deleted, smartContract, otherReceiverSigRequired,
				proxy,
				tokenRels);

		// expect:
		assertNotEquals(subject, otherSubject);
	}

	@Test
	public void equalsWorksForProxy() {
		// given:
		otherSubject = new MerkleAccountState(
				key,
				expiry, balance, autoRenewSecs, senderThreshold, receiverThreshold,
				memo,
				deleted, smartContract, receiverSigRequired,
				otherProxy,
				tokenRels);

		// expect:
		assertNotEquals(subject, otherSubject);
	}

	@Test
	public void merkleMethodsWork() {
		// expect;
		assertEquals(MerkleAccountState.RELEASE_080_VERSION, subject.getVersion());
		assertEquals(MerkleAccountState.RUNTIME_CONSTRUCTABLE_ID, subject.getClassId());
		assertTrue(subject.isLeaf());
	}

	@Test
	public void objectContractMet() {
		// given:
		var defaultSubject = new MerkleAccountState();
		// and:
		var identicalSubject = new MerkleAccountState(
				key,
				expiry, balance, autoRenewSecs, senderThreshold, receiverThreshold,
				memo,
				deleted, smartContract, receiverSigRequired,
				proxy,
				tokenRels);
		// and:
		otherSubject = new MerkleAccountState(
				otherKey,
				otherExpiry, otherBalance, otherAutoRenewSecs, otherSenderThreshold, otherReceiverThreshold,
				otherMemo,
				otherDeleted, otherSmartContract, otherReceiverSigRequired,
				otherProxy,
				otherTokenRels);

		// expect:
		assertNotEquals(subject.hashCode(), defaultSubject.hashCode());
		assertNotEquals(subject.hashCode(), otherSubject.hashCode());
		assertEquals(subject.hashCode(), identicalSubject.hashCode());
	}
}
