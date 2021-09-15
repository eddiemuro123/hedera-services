package com.hedera.services.sigs.verification;

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

import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.legacy.exception.InvalidAccountIDException;
import com.hedera.services.sigs.annotations.PayerSigReqs;
import com.hedera.services.sigs.annotations.RetryingSigReqs;
import com.hedera.services.sigs.order.SigRequirements;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static com.hedera.services.sigs.order.CodeOrderResultFactory.CODE_ORDER_RESULT_FACTORY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_ID_DOES_NOT_EXIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;

/**
 * Encapsulates logic to determine which Hedera keys need to have valid
 * signatures for a transaction to pass precheck.
 */
@Singleton
public class PrecheckKeyReqs {
	private static final Set<ResponseCodeEnum> INVALID_ACCOUNT_STATUSES = EnumSet.of(
			INVALID_ACCOUNT_ID,
			INVALID_AUTORENEW_ACCOUNT,
			ACCOUNT_ID_DOES_NOT_EXIST
	);

	private final SigRequirements keyOrder;
	private final SigRequirements keyOrderModuloRetry;
	private final Predicate<TransactionBody> isQueryPayment;

	@Inject
	public PrecheckKeyReqs(
			@PayerSigReqs SigRequirements keyOrder,
			@RetryingSigReqs SigRequirements keyOrderModuloRetry,
			Predicate<TransactionBody> isQueryPayment
	) {
		this.keyOrder = keyOrder;
		this.keyOrderModuloRetry = keyOrderModuloRetry;
		this.isQueryPayment = isQueryPayment;
	}

	/**
	 * Returns a list of Hedera keys which must have valid signatures
	 * for the given {@link TransactionBody} to pass precheck.
	 *
	 * @param txn
	 * 		a gRPC txn.
	 * @return a list of keys precheck requires to have active signatures.
	 * @throws Exception
	 * 		if the txn does not reference valid keys.
	 */
	public List<JKey> getRequiredKeys(TransactionBody txn) throws Exception {
		List<JKey> keys = new ArrayList<>();

		addPayerKeys(txn, keys);
		if (isQueryPayment.test(txn)) {
			addQueryPaymentKeys(txn, keys);
		}

		return keys;
	}

	private void addPayerKeys(TransactionBody txn, List<JKey> keys) throws Exception {
		final var payerResult = keyOrder.keysForPayer(txn, CODE_ORDER_RESULT_FACTORY);
		if (payerResult.hasErrorReport()) {
			throw new InvalidPayerAccountException();
		}
		keys.addAll(payerResult.getOrderedKeys());
	}

	private void addQueryPaymentKeys(TransactionBody txn, List<JKey> keys) throws Exception {
		final var otherResult = keyOrderModuloRetry.keysForOtherParties(txn, CODE_ORDER_RESULT_FACTORY);
		if (otherResult.hasErrorReport()) {
			final var errorStatus = otherResult.getErrorReport();
			if (INVALID_ACCOUNT_STATUSES.contains(errorStatus)) {
				throw new InvalidAccountIDException();
			} else {
				throw new Exception();
			}
		}
		keys.addAll(otherResult.getOrderedKeys());
	}
}
