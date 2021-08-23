package com.hedera.services.ledger.accounts;

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

import com.hedera.services.ledger.TransactionalLedger;
import com.hedera.services.ledger.properties.ChangeSummaryManager;
import com.hedera.services.ledger.properties.TestAccountProperty;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.legacy.core.jproto.JKeyList;
import com.hedera.services.state.submerkle.EntityId;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;

import static com.hedera.services.ledger.accounts.AccountCustomizer.Option.AUTO_RENEW_PERIOD;
import static com.hedera.services.ledger.accounts.AccountCustomizer.Option.EXPIRY;
import static com.hedera.services.ledger.accounts.AccountCustomizer.Option.IS_DELETED;
import static com.hedera.services.ledger.accounts.AccountCustomizer.Option.IS_RECEIVER_SIG_REQUIRED;
import static com.hedera.services.ledger.accounts.AccountCustomizer.Option.IS_SMART_CONTRACT;
import static com.hedera.services.ledger.accounts.AccountCustomizer.Option.KEY;
import static com.hedera.services.ledger.accounts.AccountCustomizer.Option.MEMO;
import static com.hedera.services.ledger.accounts.AccountCustomizer.Option.PROXY;
import static com.hedera.services.ledger.properties.TestAccountProperty.FLAG;
import static com.hedera.services.ledger.properties.TestAccountProperty.OBJ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.argThat;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;

class AccountCustomizerTest {
	private TestAccountCustomizer subject;
	private ChangeSummaryManager<TestAccount, TestAccountProperty> changeManager;

	private void setupWithMockChangeManager() {
		changeManager = mock(ChangeSummaryManager.class);
		subject = new TestAccountCustomizer(changeManager);
	}

	private void setupWithLiveChangeManager() {
		subject = new TestAccountCustomizer(new ChangeSummaryManager<>());
	}

	@Test
	void directlyCustomizesAnAccount() {
		setupWithLiveChangeManager();

		final var ta = subject.isDeleted(true)
				.expiry(55L)
				.memo("Something!")
				.customizing(new TestAccount());

		assertEquals(55L, ta.value);
		assertTrue(ta.flag);
		assertEquals("Something!", ta.thing);
	}

	@Test
	void setsCustomizedProperties() {
		setupWithLiveChangeManager();
		final Long id = 1L;
		final TransactionalLedger<Long, TestAccountProperty, TestAccount> ledger = mock(TransactionalLedger.class);
		final String customMemo = "alpha bravo charlie";
		final boolean customIsReceiverSigRequired = true;

		subject
				.isReceiverSigRequired(customIsReceiverSigRequired)
				.memo(customMemo);
		subject.customize(id, ledger);

		verify(ledger).set(id, OBJ, customMemo);
		verify(ledger).set(id, FLAG, customIsReceiverSigRequired);
	}

	@Test
	void changesExpectedKeyProperty() {
		setupWithMockChangeManager();
		final JKey key = new JKeyList();

		subject.key(key);

		verify(changeManager).update(
				any(EnumMap.class),
				argThat(TestAccountCustomizer.OPTION_PROPERTIES.get(KEY)::equals),
				argThat(key::equals));
	}

	@Test
	void changesExpectedMemoProperty() {
		setupWithMockChangeManager();
		final String memo = "standardization ftw?";

		subject.memo(memo);

		verify(changeManager).update(
				any(EnumMap.class),
				argThat(TestAccountCustomizer.OPTION_PROPERTIES.get(MEMO)::equals),
				argThat(memo::equals));
	}

	@Test
	void changesExpectedProxyProperty() {
		setupWithMockChangeManager();
		final var proxy = new EntityId();

		subject.proxy(proxy);

		verify(changeManager).update(
				any(EnumMap.class),
				argThat(TestAccountCustomizer.OPTION_PROPERTIES.get(PROXY)::equals),
				argThat(proxy::equals));
	}

	@Test
	void changesExpectedExpiryProperty() {
		setupWithMockChangeManager();
		final Long expiry = 1L;

		subject.expiry(expiry.longValue());

		verify(changeManager).update(
				any(EnumMap.class),
				argThat(TestAccountCustomizer.OPTION_PROPERTIES.get(EXPIRY)::equals),
				argThat(expiry::equals));
	}

	@Test
	void changesExpectedAutoRenewProperty() {
		setupWithMockChangeManager();
		final Long autoRenew = 1L;

		subject.autoRenewPeriod(autoRenew.longValue());

		verify(changeManager).update(
				any(EnumMap.class),
				argThat(TestAccountCustomizer.OPTION_PROPERTIES.get(AUTO_RENEW_PERIOD)::equals),
				argThat(autoRenew::equals));
	}

	@Test
	void changesExpectedIsSmartContractProperty() {
		setupWithMockChangeManager();
		final Boolean isSmartContract = Boolean.TRUE;

		subject.isSmartContract(isSmartContract.booleanValue());

		verify(changeManager).update(
				any(EnumMap.class),
				argThat(TestAccountCustomizer.OPTION_PROPERTIES.get(IS_SMART_CONTRACT)::equals),
				argThat(isSmartContract::equals));
	}

	@Test
	void changesExpectedIsDeletedProperty() {
		setupWithMockChangeManager();
		final Boolean isDeleted = Boolean.TRUE;

		subject.isDeleted(isDeleted.booleanValue());

		verify(changeManager).update(
				any(EnumMap.class),
				argThat(TestAccountCustomizer.OPTION_PROPERTIES.get(IS_DELETED)::equals),
				argThat(isDeleted::equals));
	}

	@Test
	void changesExpectedReceiverSigRequiredProperty() {
		setupWithMockChangeManager();
		final Boolean isSigRequired = Boolean.FALSE;

		subject.isReceiverSigRequired(isSigRequired.booleanValue());

		verify(changeManager).update(
				any(EnumMap.class),
				argThat(TestAccountCustomizer.OPTION_PROPERTIES.get(IS_RECEIVER_SIG_REQUIRED)::equals),
				argThat(isSigRequired::equals));
	}
}
