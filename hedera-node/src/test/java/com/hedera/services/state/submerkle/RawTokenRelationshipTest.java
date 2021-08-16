package com.hedera.services.state.submerkle;

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

import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.TokenFreezeStatus;
import com.hederahashgraph.api.proto.java.TokenKycStatus;
import com.hederahashgraph.api.proto.java.TokenRelationship;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;

class RawTokenRelationshipTest {
	private static final int decimals = 5;
	private static final long num = 123;
	private static final long balance = 234;
	private static final boolean frozen = true;
	private static final boolean kyc = false;

	private MerkleToken token;
	private RawTokenRelationship subject = new RawTokenRelationship(balance, 0, 0, num, frozen, kyc);

	@BeforeEach
	void setUp() {
		token = mock(MerkleToken.class);
		given(token.symbol()).willReturn("HEYMA");
	}

	@Test
	void toStringWorks() {
		assertEquals(
				"RawTokenRelationship{token=0.0.123, balance=234, frozen=true, kycGranted=false}",
				subject.toString());
	}

	@Test
	void objectContractMet() {
		final var identicalSubject = new RawTokenRelationship(balance, 0, 0, num, frozen, kyc);
		final var otherSubject = new RawTokenRelationship(
				balance * 2, 0, 0, num - 1, !frozen, !kyc);

		assertNotEquals(null, subject);
		assertNotEquals(subject, otherSubject);
		assertEquals(subject, identicalSubject);

		assertNotEquals(subject.hashCode(), otherSubject.hashCode());
		assertEquals(subject.hashCode(), identicalSubject.hashCode());
	}

	@Test
	void grpcConversionRecognizesInapplicable() {
		given(token.decimals()).willReturn(decimals);

		final var desc = subject.asGrpcFor(token);

		commonAssertions(desc);
		assertEquals(TokenFreezeStatus.FreezeNotApplicable, desc.getFreezeStatus());
		assertEquals(TokenKycStatus.KycNotApplicable, desc.getKycStatus());
		assertEquals(decimals, desc.getDecimals());
	}

	@Test
	void grpcConversionRecognizesApplicableFrozen() {
		given(token.hasFreezeKey()).willReturn(true);

		final var desc = subject.asGrpcFor(token);

		commonAssertions(desc);
		assertEquals(TokenFreezeStatus.Frozen, desc.getFreezeStatus());
		assertEquals(TokenKycStatus.KycNotApplicable, desc.getKycStatus());
	}

	@Test
	void grpcConversionRecognizesApplicableUnfozen() {
		subject = new RawTokenRelationship(subject.getBalance(), 0, 0, subject.getTokenNum(), false, false);
		given(token.hasFreezeKey()).willReturn(true);

		final var desc = subject.asGrpcFor(token);

		commonAssertions(desc);
		assertEquals(TokenFreezeStatus.Unfrozen, desc.getFreezeStatus());
		assertEquals(TokenKycStatus.KycNotApplicable, desc.getKycStatus());
	}

	@Test
	void grpcConversionRecognizesApplicableKycRevoked() {
		given(token.hasKycKey()).willReturn(true);

		final var desc = subject.asGrpcFor(token);

		commonAssertions(desc);
		assertEquals(TokenFreezeStatus.FreezeNotApplicable, desc.getFreezeStatus());
		assertEquals(TokenKycStatus.Revoked, desc.getKycStatus());
	}

	@Test
	void grpcConversionRecognizesApplicableGranted() {
		subject = new RawTokenRelationship(subject.getBalance(), 0, 0, subject.getTokenNum(), false, true);
		given(token.hasKycKey()).willReturn(true);

		final var desc = subject.asGrpcFor(token);

		commonAssertions(desc);
		assertEquals(TokenFreezeStatus.FreezeNotApplicable, desc.getFreezeStatus());
		assertEquals(TokenKycStatus.Granted, desc.getKycStatus());
		assertEquals("HEYMA", desc.getSymbol());
	}

	private void commonAssertions(final TokenRelationship desc) {
		assertEquals(balance, desc.getBalance());
		assertEquals(IdUtils.tokenWith(num), desc.getTokenId());
	}

	@Test
	void getsId() {
		assertEquals(IdUtils.tokenWith(num), subject.id());
	}
}
