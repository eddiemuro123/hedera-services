package com.hedera.services.state.expiry;

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

import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.legacy.core.jproto.TxnId;
import com.hedera.services.legacy.core.jproto.TxnReceipt;
import com.hedera.services.records.RecordCache;
import com.hedera.services.records.TxnIdRecentHistory;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.hedera.services.state.submerkle.RichInstant;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionReceipt;
import com.swirlds.fcmap.FCMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.inOrder;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;
import static org.mockito.Mockito.times;

@RunWith(JUnitPlatform.class)
class ExpiryManagerTest {
	long a = 13257, b = 75231;
	long[] aHistorical = { 10, 20, 20, 20 };
	long[] bHistorical = { 10, 50 };
	long[] aPayer = { 55 };
	long[] bPayer = { 33 };

	long expiry = 1_234_567L;
	AccountID payer = IdUtils.asAccount("0.0.13257");

	RecordCache recordCache;
	HederaLedger ledger;
	FCMap<MerkleEntityId, MerkleAccount> accounts;
	Map<TransactionID, TxnIdRecentHistory> txnHistories;

	ExpiryManager subject;

	@BeforeEach
	public void setup() {
		accounts = new FCMap<>();
		txnHistories = new HashMap<>();
		recordCache = mock(RecordCache.class);

		ledger = mock(HederaLedger.class);

		subject = new ExpiryManager(recordCache, txnHistories);
	}

	@Test
	public void purgesAsExpected() {
		// setup:
		InOrder inOrder = inOrder(ledger);

		givenAccount(a, aHistorical, aPayer);
		givenAccount(b, bHistorical, bPayer);
		// given:
		subject.resumeTrackingFrom(accounts);

		// when:
		subject.purgeExpiredRecordsAt(33, ledger);

		// then:
		inOrder.verify(ledger).purgeExpiredRecords(asAccount(a), 33);
		inOrder.verify(ledger).purgeExpiredRecords(asAccount(b), 33);
		inOrder.verify(ledger).purgeExpiredRecords(asAccount(a), 33);
		// and:
		inOrder.verify(ledger).purgeExpiredPayerRecords(
				argThat(asAccount(b)::equals),
				longThat(l -> l == 33),
				any());
		// and:
		System.out.println("Final payerExpiries: " + subject.payerExpiries.allExpiries);
		System.out.println("Final historicalExpiries: " + subject.historicalExpiries.allExpiries);
		// and:
		verify(recordCache).forgetAnyOtherExpiredHistory(33);
	}

	private AccountID asAccount(long num) {
		return IdUtils.asAccount(String.format("0.0.%d", num));
	}

	@Test
	public void resumesTrackingAsExpected() {
		givenAccount(a, aHistorical, aPayer);
		givenAccount(b, bHistorical, bPayer);

		// when:
		txnHistories.clear();
		// and:
		subject.resumeTrackingFrom(accounts);

		// then:
		var e1 = subject.payerExpiries.allExpiries.poll();
		assertEquals(b, e1.getId());
		assertEquals(33, e1.getExpiry());
		var e2 = subject.payerExpiries.allExpiries.poll();
		assertEquals(a, e2.getId());
		assertEquals(55, e2.getExpiry());
		// and:
		assertTrue(subject.payerExpiries.allExpiries.isEmpty());
		// and:
		var e3 = subject.historicalExpiries.allExpiries.poll();
		assertEquals(a, e3.getId());
		assertEquals(10, e3.getExpiry());
		var e4 = subject.historicalExpiries.allExpiries.poll();
		assertEquals(b, e4.getId());
		assertEquals(10, e4.getExpiry());
		var e5 = subject.historicalExpiries.allExpiries.poll();
		assertEquals(a, e5.getId());
		assertEquals(20, e5.getExpiry());
		var e6 = subject.historicalExpiries.allExpiries.poll();
		assertEquals(b, e6.getId());
		assertEquals(50, e6.getExpiry());
		// and:
		long[] allPayerTs = Stream.of(aPayer, bPayer)
				.flatMap(a -> Arrays.stream(a).boxed())
				.mapToLong(Long::valueOf)
				.toArray();
		long[] allHistoryTs = Stream.of(aHistorical, bHistorical)
				.flatMap(a -> Arrays.stream(a).boxed())
				.mapToLong(Long::valueOf)
				.toArray();
		assertTrue(Arrays.stream(allPayerTs).mapToObj(t -> txnIdOf(t).toGrpc()).allMatch(txnHistories::containsKey));
		assertTrue(Arrays.stream(allHistoryTs).mapToObj(t -> txnIdOf(t).toGrpc()).noneMatch(txnHistories::containsKey));
		// and:
		assertTrue(txnHistories.values().stream().noneMatch(TxnIdRecentHistory::isStagePending));
	}

	private void givenAccount(long num, long[] historicalExpiries, long[] payerExpiries) {
		var account = new MerkleAccount();
		for (long t : payerExpiries) {
			account.payerRecords().offer(withExpiry(t));
		}
		for (long t : historicalExpiries) {
			account.records().offer(withExpiry(t));
		}
		var id = new MerkleEntityId(0, 0, num);
		accounts.put(id, account);
	}

	@Test
	public void expungesAncientHistory() {
		// given:
		var c = 13258L;
		var rec = withExpiry(c);
		var txnId = txnIdOf(c).toGrpc();
		// and:
		subject.sharedNow = c;
		// and:
		given(txnHistories.get(txnId).isForgotten()).willReturn(true);
		// and:
		var forgottenHistory = txnHistories.get(txnId);

		// when:
		subject.updateHistory(rec);

		// then:
		verify(forgottenHistory).forgetExpiredAt(c);
		// and:
		assertFalse(txnHistories.containsKey(txnId));
	}

	@Test
	public void updatesHistoryAsExpected() {
		// given:
		var c = 13258L;
		var rec = withExpiry(c);
		var txnId = txnIdOf(c).toGrpc();
		// and:
		subject.sharedNow = c;
		// and:
		given(txnHistories.get(txnId).isForgotten()).willReturn(false);

		// when:
		subject.updateHistory(rec);

		// then:
		verify(txnHistories.get(txnId)).forgetExpiredAt(c);
		// and:
		assertTrue(txnHistories.containsKey(txnId));
	}

	@Test
	public void safeWhenNoHistoryAvailable() {
		// given:
		var c = 13258L;
		var rec = withExpiry(c);
		var txnId = txnIdOf(c).toGrpc();
		// and:
		subject.sharedNow = c;
		txnHistories.remove(txnId);

		// expect:
		assertDoesNotThrow(() -> subject.updateHistory(rec));
	}

	@Test
	public void usesBestGuessSubmittingMember() {
		// setup:
		long givenPayerNum = 75231;
		var rec = withPayer(givenPayerNum);
		// and:
		var history = mock(TxnIdRecentHistory.class);

		txnHistories.put(txnIdOf(givenPayerNum).toGrpc(), history);

		// when:
		subject.stage(rec);
		subject.stage(rec);
		subject.stage(rec);
		subject.stage(rec);
		subject.stage(rec);

		// then:
		verify(history, times(5)).stage(rec);
	}

	private ExpirableTxnRecord withPayer(long num) {
		return new ExpirableTxnRecord(
				TxnReceipt.fromGrpc(TransactionReceipt.newBuilder().setStatus(SUCCESS).build()),
				"NOPE".getBytes(),
				txnIdOf(num),
				RichInstant.fromJava(Instant.now()),
				null,
				0,
				null,
				null,
				null
		);
	}

	private ExpirableTxnRecord withExpiry(long t) {
		var grpcTxn = txnIdOf(t).toGrpc();
		txnHistories.put(grpcTxn, mock(TxnIdRecentHistory.class));
		var r = new ExpirableTxnRecord(
				TxnReceipt.fromGrpc(TransactionReceipt.newBuilder().setStatus(SUCCESS).build()),
				"NOPE".getBytes(),
				txnIdOf(t),
				RichInstant.fromJava(Instant.now()),
				null,
				0,
				null,
				null,
				null
		);
		r.setExpiry(t);
		return r;
	}

	private TxnId txnIdOf(long t) {
		return TxnId.fromGrpc(TransactionID.newBuilder().
						setAccountID(IdUtils.asAccount(String.format("0.0.%d", t))).build());
	}

	@Test
	public void addsExpectedExpiryForPayer() {
		// setup:
		subject.payerExpiries = (MonotonicFullQueueExpiries<Long>) mock(MonotonicFullQueueExpiries.class);

		// when:
		subject.trackPayerRecord(payer, expiry);

		// then:
		verify(subject.payerExpiries).track(Long.valueOf(13257), expiry);
	}

	@Test
	public void addsExpectedExpiryForThreshold() {
		// setup:
		subject.historicalExpiries = (MonotonicFullQueueExpiries<Long>) mock(MonotonicFullQueueExpiries.class);

		// when:
		subject.trackHistoricalRecord(payer, expiry);

		// then:
		verify(subject.historicalExpiries).track(Long.valueOf(13257), expiry);
	}
}