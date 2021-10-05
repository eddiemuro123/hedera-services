package com.hedera.services.txns.schedule;

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
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.context.TransactionContext;
import com.hedera.services.keys.InHandleActivationHelper;
import com.hedera.services.legacy.core.jproto.JEd25519Key;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.state.merkle.MerkleSchedule;
import com.hedera.services.store.schedule.ScheduleStore;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.ScheduleSignTransactionBody;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;

import static com.hedera.services.txns.schedule.SigMapScheduleClassifierTest.pretendKeyStartingWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_ALREADY_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_ALREADY_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SOME_SIGNATURES_WERE_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ScheduleSignTransitionLogicTest {
    private ScheduleStore store;
    private PlatformTxnAccessor accessor;
    private TransactionContext txnCtx;
    final TransactionID scheduledTxnId = TransactionID.newBuilder()
            .setAccountID(IdUtils.asAccount("0.0.2"))
            .setScheduled(true)
            .build();
    private JKey payerKey = new JEd25519Key(pretendKeyStartingWith("payer"));
    private Optional<List<JKey>> validScheduleKeys = Optional.of(
            List.of(new JEd25519Key(pretendKeyStartingWith("scheduled"))));

    private TransactionBody scheduleSignTxn;

    InHandleActivationHelper activationHelper;
    private SignatureMap sigMap;
    private ScheduleExecutor executor;
    private MockedStatic<ScheduleSignHelper> scheduleSignHelper;

    private ScheduleSignTransitionLogic subject;
    private ScheduleID scheduleId = IdUtils.asSchedule("1.2.3");
    private MerkleSchedule schedule;

    @BeforeEach
    private void setup() throws InvalidProtocolBufferException {
        store = mock(ScheduleStore.class);
        accessor = mock(PlatformTxnAccessor.class);
        executor = mock(ScheduleExecutor.class);
        activationHelper = mock(InHandleActivationHelper.class);
        txnCtx = mock(TransactionContext.class);
        schedule = mock(MerkleSchedule.class);
        given(txnCtx.activePayerKey()).willReturn(payerKey);
        scheduleSignHelper = mockStatic(ScheduleSignHelper.class);
        scheduleSignHelper.when(() -> ScheduleSignHelper.signingOutcome(
                any(), any(), any(), any(), any())).thenReturn(Pair.of(OK, true));
        given(executor.processExecution(scheduleId, store, txnCtx)).willReturn(OK);

        subject = new ScheduleSignTransitionLogic(store, txnCtx, activationHelper, executor);

    }

    @AfterEach
    void tearDown() {
        scheduleSignHelper.close();
    }

    @Test
    void hasCorrectApplicability() {
        givenValidTxnCtx();
        // expect:
        assertTrue(subject.applicability().test(scheduleSignTxn));
        assertFalse(subject.applicability().test(TransactionBody.getDefaultInstance()));
    }

    @Test
    void setsFailInvalidIfUnhandledException() {
        givenValidTxnCtx();
        // and:

        scheduleSignHelper.when(() -> ScheduleSignHelper.signingOutcome(
                any(), any(), any(), any(), any())).thenThrow(IllegalArgumentException.class);

        // when:
        subject.doStateTransition();

        // then:
        verify(txnCtx).setStatus(FAIL_INVALID);
    }

    @Test
    void failsOnInvalidScheduleId() {
        givenCtx(true);
        // expect:
        assertEquals(INVALID_SCHEDULE_ID, subject.validate(scheduleSignTxn));
    }

    @Test
    void acceptsValidTxn() {
        givenValidTxnCtx();

        // expect:
        assertEquals(OK, subject.semanticCheck().apply(scheduleSignTxn));
    }

    @Test
    void abortsImmediatelyIfScheduleIsExecuted() throws InvalidProtocolBufferException {
        givenValidTxnCtx();
        given(store.get(scheduleId)).willReturn(schedule);
        given(schedule.isExecuted()).willReturn(true);

        // when:
        subject.doStateTransition();

        // and:
        verify(txnCtx, never()).setScheduledTxnId(scheduledTxnId);
        verify(executor, never()).processExecution(scheduleId, store, txnCtx);
        verify(txnCtx).setStatus(SCHEDULE_ALREADY_EXECUTED);
    }

    @Test
    void abortsImmediatelyIfScheduleIsDeleted() throws InvalidProtocolBufferException {
        givenValidTxnCtx();
        given(store.get(scheduleId)).willReturn(schedule);
        given(schedule.isDeleted()).willReturn(true);

        // when:
        subject.doStateTransition();

        // and:
        verify(txnCtx, never()).setScheduledTxnId(scheduledTxnId);
        verify(executor, never()).processExecution(scheduleId, store, txnCtx);
        verify(txnCtx).setStatus(SCHEDULE_ALREADY_DELETED);
    }

    @Test
    void followsHappyPath() throws InvalidProtocolBufferException {
        givenValidTxnCtx();
        given(store.get(scheduleId)).willReturn(schedule);
        given(schedule.scheduledTransactionId()).willReturn(scheduledTxnId);

        // when:
        subject.doStateTransition();

        // and:
		verify(txnCtx).setScheduledTxnId(scheduledTxnId);
		verify(executor).processExecution(scheduleId, store, txnCtx);
        verify(txnCtx).setStatus(SUCCESS);
    }

    @Test
    void execsOnlyIfReady() throws InvalidProtocolBufferException {
        givenValidTxnCtx();
        given(store.get(scheduleId)).willReturn(schedule);
        given(schedule.scheduledTransactionId()).willReturn(scheduledTxnId);
        scheduleSignHelper.when(() -> ScheduleSignHelper.signingOutcome(
                any(), any(), any(), any(), any())).thenReturn(Pair.of(OK, false));

        // when:
        subject.doStateTransition();

        // and:
        verify(txnCtx).setStatus(SUCCESS);
        // and:
        verify(executor, never()).processExecution(scheduleId, store, txnCtx);
    }

    @Test
    void shortCircuitsOnNonOkSigningOutcome() throws InvalidProtocolBufferException {
        givenValidTxnCtx();
        given(store.get(scheduleId)).willReturn(schedule);

        scheduleSignHelper.when(() -> ScheduleSignHelper.signingOutcome(
                any(), any(), any(), any(), any())).thenReturn(Pair.of(SOME_SIGNATURES_WERE_INVALID, true));

        // when:
        subject.doStateTransition();

        // and:
        verify(txnCtx).setStatus(SOME_SIGNATURES_WERE_INVALID);
        // and:
        verify(executor, never()).processExecution(scheduleId, store, txnCtx);
    }

    @Test
    void rejectsInvalidScheduleId() {
        givenCtx(true);
        assertEquals(INVALID_SCHEDULE_ID, subject.semanticCheck().apply(scheduleSignTxn));
    }

    private void givenValidTxnCtx() {
        givenCtx(false);
    }

    private void givenCtx(boolean invalidScheduleId) {
        sigMap = SignatureMap.newBuilder().addSigPair(
                SignaturePair.newBuilder()
                        .setPubKeyPrefix(ByteString.copyFromUtf8("a"))
                        .build())
                .build();
        given(accessor.getSigMap()).willReturn(sigMap);

        var builder = TransactionBody.newBuilder();
        var scheduleSign = ScheduleSignTransactionBody.newBuilder()
                .setScheduleID(scheduleId);
        if (invalidScheduleId) {
            scheduleSign.clearScheduleID();
        }

        builder.setScheduleSign(scheduleSign);

        scheduleSignTxn = builder.build();

        given(accessor.getTxn()).willReturn(scheduleSignTxn);
        given(txnCtx.accessor()).willReturn(accessor);
    }
}
