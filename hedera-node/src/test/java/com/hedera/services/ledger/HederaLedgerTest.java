package com.hedera.services.ledger;

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

import com.hedera.services.exceptions.DeletedAccountException;
import com.hedera.services.exceptions.InconsistentAdjustmentsException;
import com.hedera.services.exceptions.InsufficientFundsException;
import com.hedera.services.exceptions.NonZeroNetTransfersException;
import com.hedera.services.ledger.accounts.FCMapBackingAccounts;
import com.hedera.services.ledger.accounts.HashMapBackingAccounts;
import com.hedera.services.ledger.accounts.HederaAccountCustomizer;
import com.hedera.services.ledger.properties.ChangeSummaryManager;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.records.AccountRecordsHistorian;
import com.hedera.services.txns.diligence.ScopedDuplicateClassifier;
import com.hedera.test.utils.IdUtils;
import com.hedera.test.utils.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransferList;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.legacy.core.jproto.JContractIDKey;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.swirlds.common.crypto.CryptoFactory;
import com.swirlds.fcmap.FCMap;
import com.swirlds.fcqueue.FCQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static com.hedera.services.utils.EntityIdUtils.asContract;
import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hedera.services.legacy.core.jproto.JKey.mapKey;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.*;
import static com.hedera.services.ledger.properties.AccountProperty.*;
import static com.hedera.services.exceptions.InsufficientFundsException.*;

@RunWith(JUnitPlatform.class)
public class HederaLedgerTest {
	final long NEXT_ID = 1_000_000L;
	final long MISC_BALANCE = 1_234L;
	final long RAND_BALANCE = 2_345L;
	final long GENESIS_BALANCE = 50_000_000_000L;
	final HederaAccountCustomizer noopCustomizer = new HederaAccountCustomizer();
	final AccountID misc = AccountID.newBuilder().setAccountNum(1_234).build();
	final AccountID rand = AccountID.newBuilder().setAccountNum(2_345).build();
	final AccountID deleted = AccountID.newBuilder().setAccountNum(3_456).build();
	final AccountID genesis = AccountID.newBuilder().setAccountNum(2).build();

	FCMapBackingAccounts backingAccounts;
	FCMap<MerkleEntityId, MerkleAccount> backingMap;

	HederaLedger subject;
	EntityIdSource ids;
	AccountRecordsHistorian historian;
	ScopedDuplicateClassifier duplicateClassifier = mock(ScopedDuplicateClassifier.class);
	TransactionalLedger<AccountID, AccountProperty, MerkleAccount> ledger;

	@BeforeEach
	private void setupWithMockLedger() {
		ids = new EntityIdSource() {
			long nextId = NEXT_ID;

			@Override
			public AccountID newAccountId(AccountID newAccountSponsor) {
				return AccountID.newBuilder().setAccountNum(nextId++).build();
			}

			@Override
			public FileID newFileId(AccountID newFileSponsor) {
				return FileID.newBuilder().setFileNum(nextId++).build();
			}
		};
		ledger = mock(TransactionalLedger.class);
		addToLedger(misc, MISC_BALANCE, noopCustomizer);
		addToLedger(rand, RAND_BALANCE, noopCustomizer);
		addToLedger(genesis, GENESIS_BALANCE, noopCustomizer);
		addDeletedAccountToLedger(deleted, noopCustomizer);
		historian = mock(AccountRecordsHistorian.class);
		subject = new HederaLedger(ids, historian, duplicateClassifier, ledger);
	}

	private void setupWithLiveLedger() {
		ledger = new TransactionalLedger<>(
				AccountProperty.class,
				() -> new MerkleAccount(),
				new HashMapBackingAccounts(),
				new ChangeSummaryManager<>());
		subject = new HederaLedger(ids, historian, duplicateClassifier, ledger);
	}

	private void setupWithLiveFcBackedLedger() {
		backingMap = new FCMap<>(new MerkleEntityId.Provider(), MerkleAccount.LEGACY_PROVIDER);
		backingAccounts = new FCMapBackingAccounts(backingMap);
		MerkleAccount genesisAccount = new MerkleAccount();
		try {
			genesisAccount.setBalance(50_000_000_000L);
			new HederaAccountCustomizer()
					.key(new JContractIDKey(0, 0, 2))
					.customizing(genesisAccount);
		} catch (Exception impossible) {}
		backingAccounts.replace(genesis, genesisAccount);
		ledger = new TransactionalLedger<>(
				AccountProperty.class,
				() -> new MerkleAccount(),
				backingAccounts,
				new ChangeSummaryManager<>());
		subject = new HederaLedger(ids, historian, duplicateClassifier, ledger);
	}

	@Test
	public void backingFcRootHashDoesDependsOnDeleteOrder() {
		// when:
		setupWithLiveFcBackedLedger();
		ledger.setKeyComparator(HederaLedger.ACCOUNT_ID_COMPARATOR);
		commitNewSpawns(50, 100);
		CryptoFactory.getInstance().digestTreeSync(backingMap);
		byte[] firstPreHash = backingMap.getRootHash().getValue();
		commitDestructions(50, 55);
		CryptoFactory.getInstance().digestTreeSync(backingMap);
		byte[] firstPostHash = backingMap.getRootHash().getValue();

		// and:
		setupWithLiveFcBackedLedger();
		ledger.setKeyComparator(HederaLedger.ACCOUNT_ID_COMPARATOR);
		commitNewSpawns(50, 100);
		CryptoFactory.getInstance().digestTreeSync(backingMap);
		byte[] secondPreHash = backingMap.getRootHash().getValue();
		ledger.setKeyComparator(HederaLedger.ACCOUNT_ID_COMPARATOR.reversed());
		commitDestructions(50, 55);
		CryptoFactory.getInstance().digestTreeSync(backingMap);
		byte[] secondPostHash = backingMap.getRootHash().getValue();

		// then:
		assertTrue(Arrays.equals(firstPreHash, secondPreHash));
		assertFalse(Arrays.equals(firstPostHash, secondPostHash));
	}

	@Test
	public void backingFcRootHashDependsOnUpdateOrder() {
		// when:
		setupWithLiveFcBackedLedger();
		ledger.setKeyComparator(HederaLedger.ACCOUNT_ID_COMPARATOR);
		commitNewSpawns(50, 100);
		CryptoFactory.getInstance().digestTreeSync(backingMap);
		byte[] firstHash = backingMap.getRootHash().getValue();

		// and:
		setupWithLiveFcBackedLedger();
		commitNewSpawns(50, 100);
		CryptoFactory.getInstance().digestTreeSync(backingMap);
		byte[] secondHash = backingMap.getRootHash().getValue();

		// then:
		assertFalse(Arrays.equals(firstHash, secondHash));
	}

	private void commitDestructions(long seqStart, long seqEnd) {
		subject.begin();
		LongStream.rangeClosed(seqStart, seqEnd)
				.mapToObj(n -> AccountID.newBuilder().setAccountNum(n).build())
				.forEach(id -> subject.destroy(id));
		subject.commit();
	}

	private void commitNewSpawns(long seqStart, long seqEnd) {
		long initialBalance = 1_000L;

		subject.begin();
		subject.adjustBalance(genesis, (seqEnd - seqStart + 1) * -initialBalance);
		LongStream.rangeClosed(seqStart, seqEnd)
				.mapToObj(n -> AccountID.newBuilder().setAccountNum(n).build())
				.forEach(id -> subject.spawn(
						id,
						initialBalance,
						new HederaAccountCustomizer()
								.key(uncheckedMap(Key.newBuilder().setContractID(asContract(id)).build()))));
		subject.commit();
	}

	private JKey uncheckedMap(Key key) {
		try {
			return mapKey(key);
		} catch (Exception impossible) {
			throw new IllegalStateException("Impossible!");
		}
	}

	@Test
	public void delegatesDestroy() {
		// when:
		subject.destroy(genesis);

		// then:
		verify(ledger).destroy(genesis);
	}

	@Test
	public void indicatesNoChangeSetIfNotInTx() {
		// when:
		String summary = subject.currentChangeSet();

		// then:
		verify(ledger, never()).changeSetSoFar();
		assertEquals(HederaLedger.NO_ACTIVE_TXN_CHANGE_SET, summary);
	}

	@Test
	public void delegatesChangeSetIfInTxn() {
		// setup:
		String zeroingGenesis = "{0.0.2: [BALANCE -> 0]}";

		given(ledger.isInTransaction()).willReturn(true);
		given(ledger.changeSetSoFar()).willReturn(zeroingGenesis);

		// when:
		String summary = subject.currentChangeSet();

		// then:
		verify(ledger).changeSetSoFar();
		assertEquals(zeroingGenesis, summary);
	}

	@Test
	public void delegatesGet() {
		// setup:
		MerkleAccount fakeGenesis = new MerkleAccount();

		given(ledger.get(genesis)).willReturn(fakeGenesis);

		// expect:
		assertTrue(fakeGenesis == subject.get(genesis));
	}

	@Test
	public void delegatesExists() {
		// given:
		AccountID missing = asAccount("55.66.77");

		// when:
		boolean hasMissing = subject.exists(missing);
		boolean hasGenesis = subject.exists(genesis);

		// then:
		verify(ledger, times(2)).exists(any());
		assertTrue(hasGenesis);
		assertFalse(hasMissing);
	}


	@Test
	public void setsSelfOnHistorian() {
		// expect:
		verify(historian).setLedger(subject);
	}

	@Test
	public void throwsOnCommittingInconsistentAdjustments() {
		setupWithLiveLedger();

		// when:
		subject.begin();
		subject.adjustBalance(genesis, -1L);
		System.out.println(ledger.changeSetSoFar());

		// then:
		assertThrows(InconsistentAdjustmentsException.class, () -> subject.commit());
	}

	@Test
	public void resetsNetTransfersAfterCommit() {
		setupWithLiveLedger();

		// when:
		subject.begin();
		AccountID a = subject.create(genesis, 1_000L, new HederaAccountCustomizer().memo("a"));
		System.out.println(ledger.changeSetSoFar());
		subject.commit();
		System.out.println(ledger.changeSetSoFar());
		// and:
		subject.begin();
		System.out.println(ledger.changeSetSoFar());
		AccountID b = subject.create(genesis, 2_000L, new HederaAccountCustomizer().memo("b"));
		System.out.println(ledger.changeSetSoFar());

		// then:
		assertEquals(2L, subject.netTransfersInTxn().getAccountAmountsList().size());
	}

	@Test
	public void doesntIncludeZeroAdjustsInNetTransfers() {
		setupWithLiveLedger();

		// when:
		subject.begin();
		AccountID a = subject.create(genesis, 1_000L, new HederaAccountCustomizer().memo("a"));
		subject.delete(a, genesis);
		System.out.println(ledger.changeSetSoFar());

		// then:
		assertEquals(0L, subject.netTransfersInTxn().getAccountAmountsList().size());
	}

	@Test
	public void doesntAllowDestructionOfRealCurrency() {
		setupWithLiveLedger();

		// when:
		subject.begin();
		AccountID a = subject.create(genesis, 1_000L, new HederaAccountCustomizer().memo("a"));
		subject.destroy(a);
		System.out.println(ledger.changeSetSoFar());

		// then:
		assertThrows(InconsistentAdjustmentsException.class, () -> subject.commit());
	}

	@Test
	public void allowsDestructionOfEphemeralCurrency() {
		setupWithLiveLedger();

		// when:
		subject.begin();
		AccountID a = asAccount("1.2.3");
		subject.spawn(a, 1_000L, new HederaAccountCustomizer().memo("a"));
		subject.destroy(a);
		System.out.println(ledger.changeSetSoFar());
		subject.commit();

		// then:
		assertFalse(subject.exists(a));
		assertEquals(GENESIS_BALANCE, subject.getBalance(genesis));
	}

	@Test
	public void recordsCreationOfAccountDeletedInSameTxn() {
		setupWithLiveLedger();

		// when:
		subject.begin();
		AccountID a = subject.create(genesis, 1_000L, new HederaAccountCustomizer().memo("a"));
		subject.delete(a, genesis);
		System.out.println(ledger.changeSetSoFar());
		int numNetTransfers = subject.netTransfersInTxn().getAccountAmountsCount();
		subject.commit();

		// then:
		assertEquals(0, numNetTransfers);
		assertTrue(subject.exists(a));
		assertEquals(GENESIS_BALANCE, subject.getBalance(genesis));
	}

	@Test
	public void addsRecordsBeforeCommitting() {
		setupWithLiveLedger();

		// when:
		subject.begin();
		AccountID a = subject.create(genesis, 1_000L, new HederaAccountCustomizer().memo("a"));
		subject.commit();

		// then:
		verify(historian).addNewRecords();
	}

	@Test
	public void incorporatesDuplicityImplicationsBeforeCommitting() {
		setupWithLiveLedger();

		// when:
		subject.begin();
		AccountID a = subject.create(genesis, 1_000L, new HederaAccountCustomizer().memo("a"));
		subject.commit();

		// then:
		verify(duplicateClassifier).incorporateCommitment();
	}

	@Test
	public void resetsNetTransfersAfterRollback() {
		setupWithLiveLedger();

		// when:
		subject.begin();
		AccountID a = subject.create(genesis, 1_000L, new HederaAccountCustomizer().memo("a"));
		System.out.println(ledger.changeSetSoFar());
		subject.rollback();
		System.out.println(ledger.changeSetSoFar());
		// and:
		subject.begin();
		System.out.println(ledger.changeSetSoFar());
		AccountID b = subject.create(genesis, 2_000L, new HederaAccountCustomizer().memo("b"));
		System.out.println(ledger.changeSetSoFar());
		System.out.println(subject.netTransfersInTxn());

		// then:
		assertEquals(2L, subject.netTransfersInTxn().getAccountAmountsList().size());
	}

	@Test
	public void returnsNetTransfersInBalancedTxn() {
		setupWithLiveLedger();

		// when:
		subject.begin();
		AccountID a = subject.create(genesis, 1_000L, new HederaAccountCustomizer().memo("a"));
		AccountID b = subject.create(genesis, 2_000L, new HederaAccountCustomizer().memo("b"));
		AccountID c = subject.create(genesis, 3_000L, new HederaAccountCustomizer().memo("c"));
		AccountID d = subject.create(genesis, 4_000L, new HederaAccountCustomizer().memo("d"));
		// and:
		subject.doTransfer(d, a, 1_000L);
		subject.delete(d, b);
		subject.adjustBalance(c, 1_000L);
		subject.adjustBalance(genesis, -1_000L);
		subject.doTransfers(TxnUtils.withAdjustments(a, -500L, b, 250L, c, 250L));
		System.out.println(ledger.changeSetSoFar());

		// then:
		assertThat(
				subject.netTransfersInTxn().getAccountAmountsList(),
				containsInAnyOrder(
						AccountAmount.newBuilder().setAccountID(a).setAmount(1_500L).build(),
						AccountAmount.newBuilder().setAccountID(b).setAmount(5_250L).build(),
						AccountAmount.newBuilder().setAccountID(c).setAmount(4_250L).build(),
						AccountAmount.newBuilder().setAccountID(genesis).setAmount(-11_000L).build()));
	}

	@Test
	public void recognizesPendingCreates() {
		setupWithLiveLedger();

		// when:
		subject.begin();
		AccountID a = subject.create(genesis, 1L, new HederaAccountCustomizer().memo("a"));

		// then:
		assertTrue(subject.isPendingCreation(a));
		assertFalse(subject.isPendingCreation(genesis));
	}

	@Test
	public void delegatesToCorrectReceiveThreshProperty() {
		// when:
		subject.fundsReceivedRecordThreshold(genesis);

		// then:
		verify(ledger).get(genesis, FUNDS_RECEIVED_RECORD_THRESHOLD);
	}

	@Test
	public void delegatesToCorrectSendThreshProperty() {
		// when:
		subject.fundsSentRecordThreshold(genesis);

		// then:
		verify(ledger).get(genesis, FUNDS_SENT_RECORD_THRESHOLD);
	}

	@Test
	public void delegatesToCorrectContractProperty() {
		// when:
		subject.isSmartContract(genesis);

		// then:
		verify(ledger).get(genesis, IS_SMART_CONTRACT);
	}

	@Test
	public void delegatesToCorrectDeletionProperty() {
		// when:
		subject.isDeleted(genesis);

		// then:
		verify(ledger).get(genesis, IS_DELETED);
	}

	@Test
	public void delegatesToCorrectExpiryProperty() {
		// when:
		subject.expiry(genesis);

		// then:
		verify(ledger).get(genesis, EXPIRY);
	}

	@Test
	public void throwsOnNetTransfersIfNotInTxn() {
		// setup:
		doThrow(IllegalStateException.class).when(ledger).throwIfNotInTxn();

		// expect:
		assertThrows(IllegalStateException.class, () -> subject.netTransfersInTxn());
	}

	@Test
	public void purgesExpiredRecords() {
		// setup:
		FCQueue<ExpirableTxnRecord> records = asExpirableRecords(50L, 100L, 200L, 311L, 500L);
		addRecords(misc, records);

		// when:
		long newEarliestExpiry = subject.purgeExpiredRecords(misc, 200L);

		// then:
		assertEquals(311L, newEarliestExpiry);
		ArgumentCaptor<FCQueue> captor = ArgumentCaptor.forClass(FCQueue.class);
		verify(ledger).set(
				argThat(misc::equals),
				argThat(TRANSACTION_RECORDS::equals),
				captor.capture());
		// and:
		assertTrue(captor.getValue() == records);
		assertThat(
				((FCQueue<ExpirableTxnRecord>)captor.getValue())
						.stream()
						.map(ExpirableTxnRecord::getExpiry)
						.collect(Collectors.toList()),
				contains(311L, 500L));
	}

	@Test
	public void returnsMinusOneIfAllRecordsPurged() {
		// setup:
		FCQueue<ExpirableTxnRecord> records = asExpirableRecords(50L, 100L, 200L, 311L, 500L);
		addRecords(misc, records);
		HederaLedger.LedgerTxnEvictionStats.INSTANCE.reset();

		// when:
		long newEarliestExpiry = subject.purgeExpiredRecords(misc, 1_000L);

		// then:
		assertEquals(-1L, newEarliestExpiry);
		ArgumentCaptor<FCQueue> captor = ArgumentCaptor.forClass(FCQueue.class);
		verify(ledger).set(
				argThat(misc::equals),
				argThat(TRANSACTION_RECORDS::equals),
				captor.capture());
		// and:
		assertTrue(captor.getValue() == records);
		assertTrue(((FCQueue<ExpirableTxnRecord>)captor.getValue()).isEmpty());
		// and:
		assertEquals(5, HederaLedger.LedgerTxnEvictionStats.INSTANCE.recordsPurged());
		assertEquals(1, HederaLedger.LedgerTxnEvictionStats.INSTANCE.accountsTouched());
	}

	@Test
	public void addsNewRecordLast() {
		// setup:
		FCQueue<ExpirableTxnRecord> records = asExpirableRecords(100L, 50L, 200L, 311L);
		addRecords(misc, records);
		// and:
		ExpirableTxnRecord newRecord = asExpirableRecords(1L).peek();

		// when:
		long newEarliestExpiry = subject.addRecord(misc, newRecord);

		// then:
		assertEquals(100L, newEarliestExpiry);
		ArgumentCaptor<FCQueue> captor = ArgumentCaptor.forClass(FCQueue.class);
		verify(ledger).set(
				argThat(misc::equals),
				argThat(TRANSACTION_RECORDS::equals),
				captor.capture());
		// and:
		assertTrue(captor.getValue() == records);
		assertThat(
				((FCQueue<ExpirableTxnRecord>)captor.getValue())
						.stream()
						.map(ExpirableTxnRecord::getExpiry)
						.collect(Collectors.toList()),
				contains(100L, 50L, 200L, 311L, 1L));
	}

	@Test
	public void throwsOnUnderfundedCreate() {
		// expect:
		assertThrows(InsufficientFundsException.class, () ->
				subject.create(rand, RAND_BALANCE + 1, noopCustomizer));
	}

	@Test
	public void performsFundedCreate() {
		// given:
		HederaAccountCustomizer customizer = mock(HederaAccountCustomizer.class);
		// and:
		given(ledger.existsPending(IdUtils.asAccount(String.format("0.0.%d", NEXT_ID)))).willReturn(true);

		// when:
		AccountID created = subject.create(rand, 1_000L, customizer);

		// then:
		assertEquals(NEXT_ID, created.getAccountNum());
		verify(ledger).set(rand, BALANCE, RAND_BALANCE - 1_000L);
		verify(ledger).create(created);
		verify(ledger).set(created, BALANCE, 1_000L);
		verify(customizer).customize(created, ledger);
	}

	@Test
	public void performsUnconditionalSpawn() {
		// given:
		HederaAccountCustomizer customizer = mock(HederaAccountCustomizer.class);
		AccountID contract = asAccount("1.2.3");
		long balance = 1_234L;
		// and:
		given(ledger.existsPending(contract)).willReturn(true);

		// when:
		subject.spawn(contract, balance, customizer);

		// then:
		verify(ledger).create(contract);
		verify(ledger).set(contract, BALANCE, balance);
		verify(customizer).customize(contract, ledger);
	}

	@Test
	public void deletesGivenAccount() {
		// when:
		subject.delete(rand, misc);

		// expect:
		verify(ledger).set(rand, BALANCE, 0L);
		verify(ledger).set(misc, BALANCE, MISC_BALANCE + RAND_BALANCE);
		verify(ledger).set(rand, IS_DELETED, true);
	}

	@Test
	public void throwsOnCustomizingDeletedAccount() {
		// expect:
		assertThrows(DeletedAccountException.class, () -> subject.customize(deleted, noopCustomizer));
	}

	@Test
	public void customizesGivenAccount() {
		// given:
		HederaAccountCustomizer customizer = mock(HederaAccountCustomizer.class);

		// when:
		subject.customize(rand, customizer);

		// then:
		verify(customizer).customize(rand, ledger);

	}

	@Test
	public void throwsOnTransferWithDeletedFromAccount() {
		// setup:
		DeletedAccountException e = null;

		// when:
		try {
			subject.doTransfer(deleted, misc, 1L);
		} catch (DeletedAccountException aide) {
			e = aide;
		}

		// then:
		assertEquals("0.0.3456", e.getMessage());
		verify(ledger, never()).set(any(), any(), any());
	}

	@Test
	public void throwsOnTransferWithDeletedToAccount() {
		// setup:
		DeletedAccountException e = null;

		// when:
		try {
			subject.doTransfer(misc, deleted, 1L);
		} catch (DeletedAccountException aide) {
			e = aide;
		}

		// then:
		assertEquals("0.0.3456", e.getMessage());
		verify(ledger, never()).set(any(), any(), any());
	}

	@Test
	public void throwsOnTransfersWithDeleted() {
		// given:
		TransferList accountAmounts = TxnUtils.withAdjustments(misc, 1, deleted, -2, genesis, 1);
		DeletedAccountException e = null;

		// expect:
		try {
			subject.doTransfers(accountAmounts);
		} catch (DeletedAccountException aide) {
			e = aide;
		}

		// then:
		assertEquals("0.0.3456", e.getMessage());
		verify(ledger, never()).set(any(), any(), any());
	}

	@Test
	public void doesReasonableTransfers() {
		// given:
		TransferList accountAmounts = TxnUtils.withAdjustments(misc, 1, rand, -2, genesis, 1);

		// expect:
		subject.doTransfers(accountAmounts);

		// then:
		verify(ledger).set(misc, BALANCE, MISC_BALANCE + 1);
		verify(ledger).set(rand, BALANCE, RAND_BALANCE - 2);
		verify(ledger).set(genesis, BALANCE, GENESIS_BALANCE + 1);
	}

	@Test
	public void throwsOnImpossibleTransfers() {
		// given:
		TransferList accountAmounts = TxnUtils.withAdjustments(misc, 1, rand, 2, genesis, 3);

		// expect:
		assertThrows(NonZeroNetTransfersException.class, () -> subject.doTransfers(accountAmounts));
	}

	@Test
	public void doesReasonableTransfer() {
		// setup:
		long amount = 1_234L;

		// when:
		subject.doTransfer(genesis, misc, amount);

		// then:
		verify(ledger).set(genesis, BALANCE, GENESIS_BALANCE - amount);
		verify(ledger).set(misc, BALANCE, MISC_BALANCE + amount);
	}

	@Test
	public void throwsOnImpossibleTransferWithBrokerPayer() {
		// setup:
		long amount = GENESIS_BALANCE + 1;
		InsufficientFundsException e = null;

		// when:
		try {
			subject.doTransfer(genesis, misc, amount);
		} catch (InsufficientFundsException ibce) {
			e = ibce;
		}

		// then:
		assertEquals(messageFor(genesis, -1 * amount), e.getMessage());
		verify(ledger, never()).set(any(), any(), any());
	}

	@Test
	public void makesPossibleAdjustment() {
		// setup:
		long amount = -1 * GENESIS_BALANCE / 2;

		// when:
		subject.adjustBalance(genesis, amount);

		// then:
		verify(ledger).set(genesis, BALANCE, GENESIS_BALANCE + amount);
	}

	@Test
	public void throwsOnNegativeBalance() {
		// setup:
		long overdraftAdjustment = -1 * GENESIS_BALANCE - 1;
		InsufficientFundsException e = null;

		// when:
		try {
			subject.adjustBalance(genesis, overdraftAdjustment);
		} catch (InsufficientFundsException ibce) {
			e = ibce;
		}

		// then:
		assertEquals(messageFor(genesis, overdraftAdjustment), e.getMessage());
		verify(ledger, never()).set(any(), any(), any());
	}

	@Test
	public void forwardsGetBalanceCorrectly() {
		// when:
		long balance = subject.getBalance(genesis);

		// then
		assertEquals(GENESIS_BALANCE, balance);
	}

	@Test
	public void forwardsTransactionalSemantics() {
		// setup:
		InOrder inOrder = inOrder(ledger);

		// when:
		subject.begin();
		subject.commit();
		subject.begin();
		subject.rollback();

		// then:
		inOrder.verify(ledger).begin();
		inOrder.verify(ledger).commit();
		inOrder.verify(ledger).begin();
		inOrder.verify(ledger).rollback();
	}

	private void addToLedger(AccountID id, long balance, HederaAccountCustomizer customizer) {
		when(ledger.get(id, EXPIRY)).thenReturn(1_234_567_890L);
		when(ledger.get(id, BALANCE)).thenReturn(balance);
		when(ledger.get(id, IS_DELETED)).thenReturn(false);
		when(ledger.get(id, IS_SMART_CONTRACT)).thenReturn(false);
		when(ledger.get(id, FUNDS_SENT_RECORD_THRESHOLD)).thenReturn(1L);
		when(ledger.get(id, FUNDS_RECEIVED_RECORD_THRESHOLD)).thenReturn(2L);
		when(ledger.exists(id)).thenReturn(true);
	}
	private void addDeletedAccountToLedger(AccountID id, HederaAccountCustomizer customizer) {
		when(ledger.get(id, BALANCE)).thenReturn(0L);
		when(ledger.get(id, IS_DELETED)).thenReturn(true);
	}
	private void addRecords(AccountID id, FCQueue<ExpirableTxnRecord> records) {
		when(ledger.get(id, TRANSACTION_RECORDS)).thenReturn(records);
	}
	FCQueue<ExpirableTxnRecord> asExpirableRecords(long... expiries) {
		FCQueue<ExpirableTxnRecord> records = new FCQueue<>(ExpirableTxnRecord.LEGACY_PROVIDER);
		for (int i = 0; i < expiries.length; i++) {
			ExpirableTxnRecord record = new ExpirableTxnRecord();
			record.setExpiry(expiries[i]);
			records.offer(record);
		}
		return records;
	}
}
