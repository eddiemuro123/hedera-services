package com.hedera.services.store.models;

import com.hedera.services.exceptions.InvalidTransactionException;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.test.factories.scenarios.TxnHandlingScenario;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static org.junit.jupiter.api.Assertions.*;

class TokenRelationshipTest {
	private final Id tokenId = new Id(0, 0, 1234);
	private final Id accountId = new Id(1, 0, 1234);
	private final long balance = 1_234L;
	private final JKey kycKey = TxnHandlingScenario.TOKEN_KYC_KT.asJKeyUnchecked();
	private final JKey freezeKey = TxnHandlingScenario.TOKEN_FREEZE_KT.asJKeyUnchecked();

	private Token token;
	private Account account;

	private TokenRelationship subject;

	@BeforeEach
	void setUp() {
		token = new Token(tokenId);
		account = new Account(accountId);
		
		subject = new TokenRelationship(token, account);
		subject.initBalance(balance);
	}

	@Test
	void cannotChangeBalanceIfFrozenForToken() {
		// given:
		token.setFreezeKey(freezeKey);
		subject.setFrozen(true);

		assertFailsWith(() -> subject.setBalance(balance + 1), ACCOUNT_FROZEN_FOR_TOKEN);
	}

	@Test
	void canChangeBalanceIfUnfrozenForToken() {
		// given:
		token.setFreezeKey(freezeKey);
		subject.setFrozen(false);

		// when:
		subject.setBalance(balance + 1);

		// then:
		assertEquals(1, subject.getBalanceChange());
	}

	@Test
	void canChangeBalanceIfNoFreezeKey() {
		// given:
		subject.setFrozen(true);

		// when:
		subject.setBalance(balance + 1);

		// then:
		assertEquals(1, subject.getBalanceChange());
	}

	@Test
	void cannotChangeBalanceIfKycNotGranted() {
		// given:
		token.setKycKey(kycKey);
		subject.setKycGranted(false);

		assertFailsWith(() -> subject.setBalance(balance + 1), ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN);
	}

	@Test
	void canChangeBalanceIfKycGranted() {
		// given:
		token.setKycKey(kycKey);
		subject.setKycGranted(true);

		// when:
		subject.setBalance(balance + 1);

		// then:
		assertEquals(1, subject.getBalanceChange());
	}

	@Test
	void canChangeBalanceIfNoKycKey() {
		// given:
		subject.setKycGranted(false);

		// when:
		subject.setBalance(balance + 1);

		// then:
		assertEquals(1, subject.getBalanceChange());
	}

	private void assertFailsWith(Runnable something, ResponseCodeEnum status) {
		var ex = assertThrows(InvalidTransactionException.class, something::run);
		assertEquals(status, ex.getResponseCode());
	}
}