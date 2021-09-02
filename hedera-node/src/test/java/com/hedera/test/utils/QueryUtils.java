package com.hedera.test.utils;

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

import com.hederahashgraph.api.proto.java.CryptoGetAccountRecordsQuery;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionGetRecordQuery;
import com.hederahashgraph.api.proto.java.TransactionID;

import static com.hedera.test.factories.scenarios.TxnHandlingScenario.COMPLEX_KEY_ACCOUNT_KT;
import static com.hedera.test.utils.TxnUtils.payerSponsoredTransfer;

public final class QueryUtils {
	public static final String payer = "0.0.12345";
	public static final String node = "0.0.3";

	public static TransactionGetRecordQuery txnRecordQuery(final TransactionID txnId) {
		return TransactionGetRecordQuery.newBuilder().setTransactionID(txnId).build();
	}

	public static TransactionGetRecordQuery txnRecordQuery(final TransactionID txnId, final ResponseType type) {
		return txnRecordQuery(txnId, type, false);
	}

	public static TransactionGetRecordQuery txnRecordQuery(
			final TransactionID txnId,
			final ResponseType type,
			final boolean duplicates
	) {
		return txnRecordQuery(txnId, type, Transaction.getDefaultInstance(), duplicates);
	}

	public static TransactionGetRecordQuery txnRecordQuery(
			final TransactionID txnId,
			final ResponseType type,
			final Transaction paymentTxn) {
		return txnRecordQuery(txnId, type, paymentTxn, false);
	}

	public static TransactionGetRecordQuery txnRecordQuery(
			final TransactionID txnId,
			final ResponseType type,
			final long payment
	) {
		return txnRecordQuery(txnId, type, payment, false);
	}

	public static TransactionGetRecordQuery txnRecordQuery(
			final TransactionID txnId,
			final ResponseType type,
			final long payment,
			final boolean duplicates
	) {
		return txnRecordQuery(txnId, type, defaultPaymentTxn(payment), duplicates);
	}

	public static TransactionGetRecordQuery txnRecordQuery(
			final TransactionID txnId,
			final ResponseType type,
			final Transaction paymentTxn,
			final boolean duplicates) {
		return TransactionGetRecordQuery.newBuilder()
				.setTransactionID(txnId)
				.setHeader(queryHeaderOf(type, paymentTxn))
				.setIncludeDuplicates(duplicates)
				.build();
	}

	public static QueryHeader.Builder queryHeaderOf(final ResponseType type, final long payment) {
		return queryHeaderOf(type, defaultPaymentTxn(payment));
	}

	public static QueryHeader.Builder queryHeaderOf(final ResponseType type, final Transaction paymentTxn) {
		return QueryHeader.newBuilder().setResponseType(type).setPayment(paymentTxn);
	}

	public static Query queryOf(final TransactionGetRecordQuery op) {
		return Query.newBuilder().setTransactionGetRecord(op).build();
	}

	public static Query queryOf(final CryptoGetAccountRecordsQuery.Builder op) {
		return Query.newBuilder().setCryptoGetAccountRecords(op).build();
	}

	public static Transaction defaultPaymentTxn(final long payment) {
		Transaction txn = Transaction.getDefaultInstance();
		try {
			txn = payerSponsoredTransfer(payer, COMPLEX_KEY_ACCOUNT_KT, node, payment);
		} catch (final Throwable ignore) {
		}
		return txn;
	}
}