package com.hedera.services.txns.submission;

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

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageV3;
import com.hedera.services.context.domain.process.TxnValidityAndFeeReq;
import com.hedera.services.utils.SignedTxnAccessor;
import com.hedera.test.utils.IdUtils;
import com.hedera.test.utils.TxnUtils;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_TOO_MANY_LAYERS;
import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StructuralPrecheckTest {
	private static final int pretendSizeLimit = 1_000;
	private static final int pretendMaxMessageDepth = 42;
	private StructuralPrecheck subject;

	@BeforeEach
	void setUp() {
		subject = new StructuralPrecheck(pretendSizeLimit, pretendMaxMessageDepth);
	}

	@Test
	void mustHaveBodyBytes() {
		final var assess = subject.assess(Transaction.getDefaultInstance());

		assertExpectedFail(INVALID_TRANSACTION_BODY, assess);
	}

	@Test
	void cantMixSignedBytesWithBodyBytes() {
		final var assess = subject.assess(Transaction.newBuilder()
				.setSignedTransactionBytes(ByteString.copyFromUtf8("w/e"))
				.setBodyBytes(ByteString.copyFromUtf8("doesn't matter"))
				.build());

		assertExpectedFail(INVALID_TRANSACTION, assess);
	}

	@Test
	void cantMixSignedBytesWithSigMap() {
		final var assess = subject.assess(Transaction.newBuilder()
				.setSignedTransactionBytes(ByteString.copyFromUtf8("w/e"))
				.setSigMap(SignatureMap.getDefaultInstance())
				.build());

		assertExpectedFail(INVALID_TRANSACTION, assess);
	}

	@Test
	void cantBeOversize() {
		final var assess = subject.assess(Transaction.newBuilder()
				.setSignedTransactionBytes(ByteString.copyFromUtf8(IntStream.range(0, pretendSizeLimit)
						.mapToObj(i -> "A")
						.collect(joining())))
				.build());

		assertExpectedFail(TRANSACTION_OVERSIZE, assess);
	}

	@Test
	void mustParseViaAccessor() {
		final var assess = subject.assess(Transaction.newBuilder()
				.setSignedTransactionBytes(ByteString.copyFromUtf8("NONSENSE"))
				.build());

		assertExpectedFail(INVALID_TRANSACTION_BODY, assess);
	}

	@Test
	void cantBeUndulyNested() {
		final var weirdlyNestedKey = TxnUtils.nestKeys(Key.newBuilder(), pretendMaxMessageDepth);
		final var hostTxn = TransactionBody.newBuilder()
				.setCryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
						.setKey(weirdlyNestedKey));
		final var signedTxn = Transaction.newBuilder().setBodyBytes(hostTxn.build().toByteString()).build();

		final var assess = subject.assess(signedTxn);

		assertExpectedFail(TRANSACTION_TOO_MANY_LAYERS, assess);
	}

	@Test
	void cantOmitAFunction() {
		final var hostTxn = TransactionBody.newBuilder()
				.setTransactionID(TransactionID.newBuilder().setAccountID(IdUtils.asAccount("0.0.2")));
		final var signedTxn = Transaction.newBuilder().setBodyBytes(hostTxn.build().toByteString()).build();

		final var assess = subject.assess(signedTxn);

		assertExpectedFail(INVALID_TRANSACTION_BODY, assess);
	}

	@Test
	void canBeOk() {
		final var reasonablyNestedKey = TxnUtils.nestKeys(Key.newBuilder(), 2);
		final var hostTxn = TransactionBody.newBuilder()
				.setCryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
						.setKey(reasonablyNestedKey));
		final var signedTxn = Transaction.newBuilder().setBodyBytes(hostTxn.build().toByteString()).build();

		final var assess = subject.assess(signedTxn);

		assertEquals(OK, assess.getLeft().getValidity());
		assertNotNull(assess.getRight());
		assertEquals(HederaFunctionality.CryptoCreate, assess.getRight().getFunction());
	}

	@Test
	void computesExpectedDepth() {
		final var weirdlyNestedKey = TxnUtils.nestKeys(Key.newBuilder(), pretendMaxMessageDepth).build();
		final var expectedDepth = verboseCalc(weirdlyNestedKey);

		final var actualDepth = subject.protoDepthOf(weirdlyNestedKey);

		assertEquals(expectedDepth, actualDepth);
	}

	private int verboseCalc(final GeneratedMessageV3 msg) {
		final var fields = msg.getAllFields();
		int depth = 0;
		for (final var field : fields.values()) {
			if (field instanceof GeneratedMessageV3) {
				GeneratedMessageV3 fieldMessage = (GeneratedMessageV3) field;
				depth = Math.max(depth, verboseCalc(fieldMessage) + 1);
			} else if (field instanceof List) {
				for (final Object ele : (List) field) {
					if (ele instanceof GeneratedMessageV3) {
						depth = Math.max(depth, verboseCalc((GeneratedMessageV3) ele) + 1);
					}
				}
			}
		}
		return depth;
	}

	private void assertExpectedFail(
			final ResponseCodeEnum error,
			final Pair<TxnValidityAndFeeReq, SignedTxnAccessor> resp
	) {
		assertEquals(error, resp.getLeft().getValidity());
		assertNull(resp.getRight());
	}
}
