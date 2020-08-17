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
import com.hedera.services.ledger.accounts.HederaAccountCustomizer;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.records.AccountRecordsHistorian;
import com.hedera.services.state.EntityCreator;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TransferList;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.swirlds.fcqueue.FCQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.hedera.services.ledger.properties.AccountProperty.*;
import static com.hedera.services.utils.EntityIdUtils.readableId;
import static java.lang.Math.min;
import static com.hedera.services.txns.validation.TransferListChecks.isNetZeroAdjustment;

/**
 * Provides a ledger for Hedera Services crypto and smart contract
 * accounts with transactional semantics. Changes to the ledger are
 * <b>only</b> allowed in the scope of a transaction.
 *
 * All changes that are made during a transaction are summarized as
 * per-account changesets. These changesets are committed to a
 * wrapped {@link TransactionalLedger}; or dropped entirely in case
 * of a rollback.
 *
 * The ledger delegates history of each transaction to an injected
 * {@link AccountRecordsHistorian} by invoking its {@code addNewRecords}
 * immediately before the final {@link TransactionalLedger#commit()}.
 *
 * We should think of the ledger as using double-booked accounting,
 * (e.g., via the {@link HederaLedger#doTransfer(AccountID, AccountID, long)}
 * method); but it is necessary to provide "unsafe" single-booked
 * methods like {@link HederaLedger#adjustBalance(AccountID, long)} in
 * order to match transfer semantics the EVM expects.
 *
 * @author Michael Tinker
 */
@SuppressWarnings("unchecked")
public class HederaLedger {
	private static final Logger log = LogManager.getLogger(HederaLedger.class);
	private static final Consumer<ExpirableTxnRecord> NOOP_CB = record -> {};

	static final String NO_ACTIVE_TXN_CHANGE_SET = "{*NO ACTIVE TXN*}";
	public static final Comparator<AccountID> ACCOUNT_ID_COMPARATOR = Comparator
			.comparingLong(AccountID::getAccountNum)
			.thenComparingLong(AccountID::getShardNum)
			.thenComparingLong(AccountID::getRealmNum);

	private final EntityIdSource ids;
	private final AccountRecordsHistorian historian;
	private final TransactionalLedger<AccountID, AccountProperty, MerkleAccount> ledger;

	private final Map<AccountID, Long> priorBalances = new HashMap<>();

	private final TransferList.Builder netTransfers = TransferList.newBuilder();

	public HederaLedger(
			EntityIdSource ids,
			EntityCreator creator,
			AccountRecordsHistorian historian,
			TransactionalLedger<AccountID, AccountProperty, MerkleAccount> ledger
	) {
		this.ids = ids;
		this.ledger = ledger;
		this.historian = historian;

		creator.setLedger(this);
		historian.setLedger(this);
		historian.setCreator(creator);
	}

	/* -- TRANSACTIONAL SEMANTICS -- */
	public void begin() {
		ledger.begin();
	}

	public void rollback() {
		ledger.rollback();
		netTransfers.clear();
	}

	public void commit() {
		throwIfPendingStateIsInconsistent();
		historian.addNewRecords();
		ledger.commit();
		netTransfers.clear();
	}

	public TransferList netTransfersInTxn() {
		ledger.throwIfNotInTxn();
		int lastZeroRemoved;
		do {
			lastZeroRemoved = -1;
			for (int i = 0; i < netTransfers.getAccountAmountsCount(); i++) {
				if (netTransfers.getAccountAmounts(i).getAmount() == 0) {
					netTransfers.removeAccountAmounts(i);
					lastZeroRemoved = i;
					break;
				}
			}
		} while (lastZeroRemoved != -1);
		return netTransfers.build();
	}

	public String currentChangeSet() {
		if (ledger.isInTransaction()) {
			return ledger.changeSetSoFar();
		} else {
			return NO_ACTIVE_TXN_CHANGE_SET;
		}
	}

	/* -- CURRENCY MANIPULATION -- */
	public long getBalance(AccountID id) {
		return (long)ledger.get(id, BALANCE);
	}

	public void adjustBalance(AccountID id, long adjustment) {
		long newBalance = computeNewBalance(id, adjustment);
		setBalance(id, newBalance);

		updateXfers(id, adjustment);
	}

	public void doTransfer(AccountID from, AccountID to, long adjustment) {
		long newFromBalance = computeNewBalance(from, -1 * adjustment);
		long newToBalance = computeNewBalance(to, adjustment);
		setBalance(from, newFromBalance);
		setBalance(to, newToBalance);

		updateXfers(from, -1 * adjustment);
		updateXfers(to, adjustment);
	}

	public void doTransfers(TransferList accountAmounts) {
		throwIfNetAdjustmentIsNonzero(accountAmounts);
		long[] newBalances = computeNewBalances(accountAmounts);
		for (int i = 0; i < newBalances.length; i++) {
			setBalance(accountAmounts.getAccountAmounts(i).getAccountID(), newBalances[i]);
		}

		for (AccountAmount aa : accountAmounts.getAccountAmountsList()) {
			updateXfers(aa.getAccountID(), aa.getAmount());
		}
	}

	/* -- ACCOUNT META MANIPULATION -- */
	public AccountID create(AccountID sponsor, long balance, HederaAccountCustomizer customizer) {
		long newSponsorBalance = computeNewBalance(sponsor, -1 * balance);
		setBalance(sponsor, newSponsorBalance);

		var id = ids.newAccountId(sponsor);
		spawn(id, balance, customizer);

		updateXfers(sponsor, -1 * balance);

		return id;
	}

	public void spawn(AccountID id, long balance, HederaAccountCustomizer customizer) {
		ledger.create(id);
		setBalance(id, balance);
		customizer.customize(id, ledger);

		updateXfers(id, balance);
	}

	public void customize(AccountID id, HederaAccountCustomizer customizer) {
		if ((boolean)ledger.get(id, IS_DELETED)) {
			throw new DeletedAccountException(id);
		}
		customizer.customize(id, ledger);
	}

	public void delete(AccountID id, AccountID beneficiary) {
		doTransfer(id, beneficiary, getBalance(id));
		ledger.set(id, IS_DELETED, true);
	}

	public void destroy(AccountID id) {
		ledger.destroy(id);
		for (int i = 0; i < netTransfers.getAccountAmountsCount(); i++) {
			if (netTransfers.getAccountAmounts(i).getAccountID().equals(id)) {
				netTransfers.removeAccountAmounts(i);
				return;
			}
		}
	}

	/* -- ACCOUNT PROPERTY ACCESS -- */
	public boolean exists(AccountID id)	 {
		return ledger.exists(id);
	}

	public long expiry(AccountID id) {
		return (long)ledger.get(id, EXPIRY);
	}

	public long fundsSentRecordThreshold(AccountID id) {
		return (long)ledger.get(id, FUNDS_SENT_RECORD_THRESHOLD);
	}

	public long fundsReceivedRecordThreshold(AccountID id) {
		return (long)ledger.get(id, FUNDS_RECEIVED_RECORD_THRESHOLD);
	}

	public boolean isSmartContract(AccountID id) {
		return (boolean)ledger.get(id, IS_SMART_CONTRACT);
	}

	public boolean isDeleted(AccountID id) {
		return (boolean)ledger.get(id, IS_DELETED);
	}

	public boolean isPendingCreation(AccountID id) {
		return ledger.existsPending(id);
	}

	public MerkleAccount get(AccountID id) {
		return ledger.get(id);
	}

	/* -- TRANSACTION HISTORY MANIPULATION -- */
	public long addRecord(AccountID id, ExpirableTxnRecord record) {
		return addReturningEarliestExpiry(id, HISTORY_RECORDS, record);
	}

	public long addPayerRecord(AccountID id, ExpirableTxnRecord record) {
		return addReturningEarliestExpiry(id, PAYER_RECORDS, record);
	}

	private long addReturningEarliestExpiry(AccountID id, AccountProperty property, ExpirableTxnRecord record) {
		FCQueue<ExpirableTxnRecord> records = (FCQueue<ExpirableTxnRecord>)ledger.get(id, property);
		records.offer(record);
		ledger.set(id, property, records);
		return records.peek().getExpiry();
	}

	public long purgeExpiredRecords(AccountID id, long now) {
		return purge(id, HISTORY_RECORDS, now, NOOP_CB);
	}

	public long purgeExpiredPayerRecords(AccountID id, long now, Consumer<ExpirableTxnRecord> cb) {
		return purge(id, PAYER_RECORDS, now, cb);
	}

	private long purge(
			AccountID id,
			AccountProperty recordsProp,
			long now,
			Consumer<ExpirableTxnRecord> cb
	) {
		FCQueue<ExpirableTxnRecord> records = (FCQueue<ExpirableTxnRecord>)ledger.get(id, recordsProp);
		int numBefore = records.size();

		long newEarliestExpiry = purgeForNewEarliestExpiry(records, now, cb);
		ledger.set(id, recordsProp, records);

		int numPurged = numBefore - records.size();
		LedgerTxnEvictionStats.INSTANCE.recordPurgedFromAnAccount(numPurged);
		log.debug("Purged {} records from account {}",
				() -> numPurged,
				() -> readableId(id));

		return newEarliestExpiry;
	}

	private long purgeForNewEarliestExpiry(
			FCQueue<ExpirableTxnRecord> records,
			long now,
			Consumer<ExpirableTxnRecord> cb
	) {
		long newEarliestExpiry = -1;
		while (!records.isEmpty() && records.peek().getExpiry() <= now) {
			cb.accept(records.poll());
		}
		if (!records.isEmpty()) {
			newEarliestExpiry = records.peek().getExpiry();
		}
		return newEarliestExpiry;
	}

	/* -- HELPERS -- */
	private boolean isLegalToAdjust(long balance, long adjustment) {
		return (balance + adjustment >= 0);
	}

	private long computeNewBalance(AccountID id, long adjustment) {
		if ((boolean)ledger.get(id, IS_DELETED)) {
			throw new DeletedAccountException(id);
		}
		long balance = getBalance(id);
		if (!isLegalToAdjust(balance, adjustment)) {
			throw new InsufficientFundsException(id, adjustment);
		}
		return balance + adjustment;
	}

	private void throwIfNetAdjustmentIsNonzero(TransferList accountAmounts) {
		if (!isNetZeroAdjustment(accountAmounts)) {
			throw new NonZeroNetTransfersException(accountAmounts);
		}
	}

	private void throwIfPendingStateIsInconsistent() {
		if (!isNetZeroAdjustment(netTransfersInTxn())) {
			throw new InconsistentAdjustmentsException();
		}
	}

	private long[] computeNewBalances(TransferList accountAmounts) {
		return accountAmounts.getAccountAmountsList()
				.stream()
				.mapToLong(aa -> computeNewBalance(aa.getAccountID(), aa.getAmount()))
				.toArray();
	}

	private void setBalance(AccountID id, long newBalance) {
		if (!priorBalances.containsKey(id)) {
			priorBalances.put(id, isPendingCreation(id) ? 0L : getBalance(id));
		}
		ledger.set(id, BALANCE, newBalance);
	}

	private void updateXfers(AccountID account, long amount) {
		int loc = 0, diff = -1;
		var soFar = netTransfers.getAccountAmountsBuilderList();
		for (; loc < soFar.size(); loc++) {
			diff = ACCOUNT_ID_COMPARATOR.compare(account, soFar.get(loc).getAccountID());
			if (diff <= 0) {
				break;
			}
		}
		if (diff == 0) {
			var aa = soFar.get(loc);
			long current = aa.getAmount();
			aa.setAmount(current + amount);
		} else {
			if (loc == soFar.size()) {
				netTransfers.addAccountAmounts(aaBuilderWith(account, amount));
			} else {
				netTransfers.addAccountAmounts(loc, aaBuilderWith(account, amount));
			}
		}
	}

	private AccountAmount.Builder aaBuilderWith(AccountID account, long amount) {
		return AccountAmount.newBuilder().setAccountID(account).setAmount(amount);
	}

	public enum LedgerTxnEvictionStats {
		INSTANCE;

		private int recordsPurged = 0;
		private int accountsTouched = 0;

		public int recordsPurged() {
			return recordsPurged;
		}

		public int accountsTouched() {
			return accountsTouched;
		}

		public void reset() {
			accountsTouched = 0;
			recordsPurged = 0;
		}

		public void recordPurgedFromAnAccount(int n) {
			accountsTouched++;
			recordsPurged += n;
		}
	}
}
