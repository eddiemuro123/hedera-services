package com.hedera.services.txns.submission;

import com.hedera.services.context.domain.security.HapiOpPermissions;
import com.hedera.services.security.ops.SystemOpAuthorization;
import com.hedera.services.security.ops.SystemOpPolicies;
import com.hedera.services.throttling.TransactionThrottling;
import com.hedera.services.utils.SignedTxnAccessor;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SystemPrecheckTest {
	private final AccountID systemPayer = IdUtils.asAccount("0.0.50");
	private final AccountID civilianPayer = IdUtils.asAccount("0.0.1234");
	private final SignedTxnAccessor civilianXferAccessor = SignedTxnAccessor.uncheckedFrom(Transaction.newBuilder()
			.setBodyBytes(TransactionBody.newBuilder()
					.setTransactionID(TransactionID.newBuilder()
							.setAccountID(civilianPayer))
					.setCryptoTransfer(CryptoTransferTransactionBody.getDefaultInstance())
					.build()
					.toByteString())
			.build());
	private final SignedTxnAccessor systemXferAccessor = SignedTxnAccessor.uncheckedFrom(Transaction.newBuilder()
			.setBodyBytes(TransactionBody.newBuilder()
					.setTransactionID(TransactionID.newBuilder()
							.setAccountID(systemPayer))
					.setCryptoTransfer(CryptoTransferTransactionBody.getDefaultInstance())
					.build()
					.toByteString())
			.build());

	@Mock
	private SystemOpPolicies systemOpPolicies;
	@Mock
	private HapiOpPermissions hapiOpPermissions;
	@Mock
	private TransactionThrottling txnThrottling;

	private SystemPrecheck subject;

	@BeforeEach
	void setUp() {
		subject = new SystemPrecheck(systemOpPolicies, hapiOpPermissions, txnThrottling);
	}

	@Test
	void rejectsUnsupportedOp() {
		given(hapiOpPermissions.permissibilityOf(CryptoTransfer, civilianPayer)).willReturn(NOT_SUPPORTED);

		// when:
		var actual = subject.screen(civilianXferAccessor);

		// then:
		assertEquals(NOT_SUPPORTED, actual);
	}

	@Test
	void rejectsUnprivilegedPayer() {
		givenPermissible(civilianPayer);
		given(systemOpPolicies.check(civilianXferAccessor)).willReturn(SystemOpAuthorization.IMPERMISSIBLE);

		// when:
		var actual = subject.screen(civilianXferAccessor);

		// then:
		assertEquals(SystemOpAuthorization.IMPERMISSIBLE.asStatus(), actual);
	}

	@Test
	void throttlesCivilianIfBusy() {
		givenPermissible(civilianPayer);
		givenPriviliged();
		given(txnThrottling.shouldThrottle(civilianXferAccessor.getTxn())).willReturn(true);

		// when:
		var actual = subject.screen(civilianXferAccessor);

		// then:
		assertEquals(BUSY, actual);
	}

	@Test
	void doesntThrottleSystemAccounts() {
		givenPermissible(systemPayer);
		givenPriviliged();

		// when:
		var actual = subject.screen(systemXferAccessor);

		// then:
		assertEquals(OK, actual);
	}

	@Test
	void okIfAllScreensPass() {
		givenPermissible(civilianPayer);
		givenPriviliged();
		givenCapacity();

		// when:
		var actual = subject.screen(civilianXferAccessor);

		// then:
		assertEquals(OK, actual);
	}

	private void givenCapacity() {
		given(txnThrottling.shouldThrottle(civilianXferAccessor.getTxn())).willReturn(false);
	}

	private void givenPermissible(AccountID payer) {
		given(hapiOpPermissions.permissibilityOf(CryptoTransfer, payer)).willReturn(OK);
	}

	private void givenPriviliged() {
		given(systemOpPolicies.check(any())).willReturn(SystemOpAuthorization.UNNECESSARY);
	}
}