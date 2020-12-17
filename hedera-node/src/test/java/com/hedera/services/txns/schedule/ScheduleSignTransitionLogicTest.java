package com.hedera.services.txns.schedule;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.suites.utils.keypairs.Ed25519PrivateKey;
import com.hedera.services.context.TransactionContext;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.legacy.proto.utils.SignatureGenerator;
import com.hedera.services.store.schedule.ScheduleStore;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.ScheduleSignTransactionBody;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.bouncycastle.crypto.KeyGenerationParameters;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.jcajce.provider.asymmetric.edec.KeyPairGeneratorSpi;
import org.bouncycastle.jcajce.provider.digest.SHA3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;

import java.security.KeyPairGenerator;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static junit.framework.TestCase.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@RunWith(JUnitPlatform.class)
public class ScheduleSignTransitionLogicTest {
    private OptionValidator validator;
    private ScheduleStore store;
    private HederaLedger ledger;
    private PlatformTxnAccessor accessor;
    private TransactionContext txnCtx;

    private TransactionBody scheduleSignTxn;

    private SignatureMap sigMap;

    private ScheduleSignTransitionLogic subject;
    private ScheduleID schedule = IdUtils.asSchedule("1.2.3");

    @BeforeEach
    private void setup() {
        validator = mock(OptionValidator.class);
        store = mock(ScheduleStore.class);
        ledger = mock(HederaLedger.class);
        accessor = mock(PlatformTxnAccessor.class);

        txnCtx = mock(TransactionContext.class);

        subject = new ScheduleSignTransitionLogic(validator, store, ledger, txnCtx);
    }

    @Test
    public void hasCorrectApplicability() {
        givenValidTxnCtx();

        // expect:
        assertTrue(subject.applicability().test(scheduleSignTxn));
        assertFalse(subject.applicability().test(TransactionBody.getDefaultInstance()));
    }

    @Test
    public void followsHappyPath() {
        // given:
        givenValidTxnCtx();

        // and:
        given(store.addSigners(eq(schedule), any())).willReturn(OK);

        // when:
        subject.doStateTransition();

        // then
        verify(store).addSigners(eq(schedule), any());
        verify(txnCtx).setStatus(SUCCESS);
    }

    @Test
    public void capturesInvalidScheduleId() {
        // given:
        givenValidTxnCtx();

        // and:
        given(store.addSigners(eq(schedule), any())).willReturn(INVALID_SCHEDULE_ID);

        // when:
        subject.doStateTransition();

        // then
        verify(store).addSigners(eq(schedule), any());
        verify(txnCtx).setStatus(INVALID_SCHEDULE_ID);
    }

    @Test
    public void capturesDeletedSchedule() {
        // given:
        givenValidTxnCtx();

        // and:
        given(store.addSigners(eq(schedule), any())).willReturn(SCHEDULE_WAS_DELETED);

        // when:
        subject.doStateTransition();

        // then
        verify(store).addSigners(eq(schedule), any());
        verify(txnCtx).setStatus(SCHEDULE_WAS_DELETED);
    }

    @Test
    public void failsOnInvalidScheduleId() {
        givenCtx(true);

        // expect:
        assertEquals(INVALID_SCHEDULE_ID, subject.validate(scheduleSignTxn));
    }

    private void givenValidTxnCtx() {
        givenCtx(false);
    }

    private void givenCtx(
            boolean invalidScheduleId
    ) {
        var keyPair = Ed25519PrivateKey.generate();
        this.sigMap = SignatureMap.newBuilder().addSigPair(
                SignaturePair.newBuilder()
                        .setPubKeyPrefix(ByteString.copyFrom(keyPair.getPublicKey().toBytes()))
                        .build()
        ).build();

        var builder = TransactionBody.newBuilder();
        var scheduleSign = ScheduleSignTransactionBody.newBuilder()
                .setSigMap(sigMap)
                .setSchedule(schedule);

        if (invalidScheduleId) {
            scheduleSign.clearSchedule();
        }

        builder.setScheduleSign(scheduleSign);

        scheduleSignTxn = builder.build();
        given(accessor.getTxn()).willReturn(scheduleSignTxn);
        given(txnCtx.accessor()).willReturn(accessor);
    }
}
