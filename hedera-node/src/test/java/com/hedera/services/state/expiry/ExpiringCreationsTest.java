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

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.records.RecordCache;
import com.hedera.services.state.serdes.DomainSerdesTest;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static com.hedera.services.state.expiry.NoopExpiringCreations.NOOP_EXPIRING_CREATIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.*;

class ExpiringCreationsTest {
	int historyTtl = 90_000, cacheTtl = 180;
	long now = 1_234_567L;
	long submittingMember = 1L;

	AccountID effPayer = IdUtils.asAccount("0.0.13257");
	TransactionRecord record = DomainSerdesTest.recordOne().asGrpc();

	RecordCache recordCache;
	HederaLedger ledger;
	ExpiryManager expiries;
	PropertySource properties;
	GlobalDynamicProperties dynamicProperties;

	ExpiringCreations subject;

	@BeforeEach
	public void setup() {
		ledger = mock(HederaLedger.class);
		expiries = mock(ExpiryManager.class);
		properties = mock(PropertySource.class);
		recordCache = mock(RecordCache.class);
		dynamicProperties = mock(GlobalDynamicProperties.class);
		given(dynamicProperties.shouldCreatePayerRecords()).willReturn(true);
		given(properties.getIntProperty("ledger.records.ttl")).willReturn(historyTtl);
		given(dynamicProperties.cacheRecordsTtl()).willReturn(cacheTtl);

		subject = new ExpiringCreations(expiries, properties, dynamicProperties);
		subject.setRecordCache(recordCache);
		subject.setLedger(ledger);
	}

	@Test
	public void noopFormDoesNothing() {
		// expect:
		Assertions.assertDoesNotThrow(() ->
				NOOP_EXPIRING_CREATIONS.setLedger(null));
		Assertions.assertThrows(UnsupportedOperationException.class, () ->
				NOOP_EXPIRING_CREATIONS.createExpiringPayerRecord(
						null, null, 0L, submittingMember));
		Assertions.assertDoesNotThrow(() ->
				NOOP_EXPIRING_CREATIONS.createExpiringHistoricalRecord(
						null, null, 0L, submittingMember));
	}

	@Test
	public void reusesLastExpirableIfSameRecord() {
		// setup:
		ArgumentCaptor<ExpirableTxnRecord> captor = ArgumentCaptor.forClass(ExpirableTxnRecord.class);

		// when:
		subject.createExpiringHistoricalRecord(effPayer, record, now, submittingMember);
		subject.createExpiringHistoricalRecord(effPayer, record, now, submittingMember);

		// then:
		verify(ledger, times(2)).addRecord(argThat(effPayer::equals), captor.capture());
		// and:
		Assertions.assertSame(captor.getAllValues().get(0), captor.getAllValues().get(1));
	}

	@Test
	public void ifNotCreatingStatePayerRecordsDirectlyTracksWithCache() {
		given(dynamicProperties.shouldCreatePayerRecords()).willReturn(false);

		// given:
		long expectedExpiry = now + cacheTtl;
		// and:
		var expected = ExpirableTxnRecord.fromGprc(record);
		expected.setExpiry(expectedExpiry);
		expected.setSubmittingMember(submittingMember);

		// when:
		var actual = subject.createExpiringPayerRecord(effPayer, record, now, submittingMember);

		// then:
		verify(ledger, never()).addPayerRecord(any(), any());
		verify(recordCache).trackForExpiry(expected);
		// and:
		verify(expiries, never()).trackPayerRecord(effPayer, expectedExpiry);
		// and:
		Assertions.assertEquals(expected, actual);
	}

	@Test
	public void addsToPayerRecordsAndTracks() {
		// setup:
		ArgumentCaptor<ExpirableTxnRecord> captor = ArgumentCaptor.forClass(ExpirableTxnRecord.class);

		// given:
		long expectedExpiry = now + cacheTtl;
		// and:
		var expected = ExpirableTxnRecord.fromGprc(record);
		expected.setExpiry(expectedExpiry);
		expected.setSubmittingMember(submittingMember);

		// when:
		var actual = subject.createExpiringPayerRecord(effPayer, record, now, submittingMember);

		// then:
		verify(ledger).addPayerRecord(argThat(effPayer::equals), captor.capture());
		// and:
		assertEquals(expectedExpiry, captor.getValue().getExpiry());
		Assertions.assertEquals(expected, actual);
		// and:
		verify(expiries).trackPayerRecord(effPayer, expectedExpiry);
	}

	@Test
	public void addsToHistoryRecordsAndTracks() {
		// setup:
		ArgumentCaptor<ExpirableTxnRecord> captor = ArgumentCaptor.forClass(ExpirableTxnRecord.class);

		// given:
		long expectedExpiry = now + historyTtl;

		// when:
		subject.createExpiringHistoricalRecord(effPayer, record, now, submittingMember);

		// then:
		verify(ledger).addRecord(argThat(effPayer::equals), captor.capture());
		// and:
		assertEquals(expectedExpiry, captor.getValue().getExpiry());
		assertEquals(submittingMember, captor.getValue().getSubmittingMember());
		// and:
		verify(expiries).trackHistoricalRecord(effPayer, expectedExpiry);
	}
}
