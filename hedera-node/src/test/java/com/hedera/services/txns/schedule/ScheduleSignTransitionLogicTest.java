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
import com.hedera.services.context.TransactionContext;
import com.hedera.services.keys.InHandleActivationHelper;
import com.hedera.services.keys.KeysHelper;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.state.merkle.MerkleSchedule;
import com.hedera.services.store.schedule.ScheduleStore;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.ScheduleSignTransactionBody;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import com.hederahashgraph.api.proto.java.TransactionBody;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import org.apache.commons.codec.DecoderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

import static com.hedera.services.legacy.core.jproto.JKey.equalUpToDecodability;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_KEY_ENCODING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
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
    private ScheduleStore store;
    private PlatformTxnAccessor accessor;
    private TransactionContext txnCtx;
    private byte[] transactionBody = TransactionBody.newBuilder()
            .setMemo("Just this")
            .build()
            .toByteArray();

    private TransactionBody scheduleSignTxn;

    InHandleActivationHelper activationHelper;
    private SignatureMap.Builder sigMap;
    private Set<JKey> jKeySet;

    private ScheduleSignTransitionLogic subject;
    private ScheduleID schedule = IdUtils.asSchedule("1.2.3");
    private final ResponseCodeEnum NOT_OK = null;

    @BeforeEach
    private void setup() {
        store = mock(ScheduleStore.class);
        accessor = mock(PlatformTxnAccessor.class);
        activationHelper = mock(InHandleActivationHelper.class);

        txnCtx = mock(TransactionContext.class);

        subject = new ScheduleSignTransitionLogic(store, txnCtx, activationHelper);
    }

    @Test
    public void hasCorrectApplicability() {
        givenValidTxnCtx();
        // expect:
        assertTrue(subject.applicability().test(scheduleSignTxn));
        assertFalse(subject.applicability().test(TransactionBody.getDefaultInstance()));
    }

    @Test
    public void setsFailInvalidIfUnhandledException() {
        givenValidTxnCtx();
        // and:
        given(store.get(any())).willThrow(IllegalArgumentException.class);

        // when:
        subject.doStateTransition();

        // then:
        verify(txnCtx).setStatus(FAIL_INVALID);
    }

    @Test
    public void failsOnInvalidScheduleId() {
        givenCtx(true, false);
        // expect:
        assertEquals(INVALID_SCHEDULE_ID, subject.validate(scheduleSignTxn));
    }

    @Test
    public void acceptsValidTxn() {
        givenValidTxnCtx();
        given(store.exists(schedule)).willReturn(true);

        // expect:
        assertEquals(OK, subject.syntaxCheck().apply(scheduleSignTxn));
    }

    @Test
    public void followsHappyPath() {
        // setup:
        MerkleSchedule present = mock(MerkleSchedule.class);
        given(present.transactionBody()).willReturn(transactionBody);

        givenValidTxnCtx();

        given(store.exists(schedule)).willReturn(true);
        // and:
        given(store.get(schedule)).willReturn(present);

        // when:
        subject.doStateTransition();

        // and:
        verify(txnCtx).setStatus(SUCCESS);
    }

    @Test
    public void rejectsInvalidScheduleId() {
        givenCtx(true, false);
        assertEquals(INVALID_SCHEDULE_ID, subject.syntaxCheck().apply(scheduleSignTxn));
    }

    private void givenValidTxnCtx() {
        givenCtx(false, false);
    }

    private void givenCtx(
            boolean invalidScheduleId,
            boolean invalidKeyEncoding
    ) {
        var pair = new KeyPairGenerator().generateKeyPair();
        byte[] pubKey = ((EdDSAPublicKey) pair.getPublic()).getAbyte();
        this.sigMap = SignatureMap.newBuilder().addSigPair(
                SignaturePair.newBuilder()
                        .setPubKeyPrefix(ByteString.copyFrom(pubKey))
                        .build()
        );

        try {
            jKeySet = new HashSet<>();
            for (SignaturePair signaturePair : this.sigMap.getSigPairList()) {
                jKeySet.add(KeysHelper.ed25519ToJKey(signaturePair.getPubKeyPrefix()));
            }
        } catch (DecoderException e) {
            e.printStackTrace();
        }

        var builder = TransactionBody.newBuilder();
        var scheduleSign = ScheduleSignTransactionBody.newBuilder()
                .setSigMap(sigMap)
                .setScheduleID(schedule);
        if (invalidScheduleId) {
            scheduleSign.clearScheduleID();
        }
        if (invalidKeyEncoding) {
            this.sigMap.clear().addSigPair(SignaturePair.newBuilder().setEd25519(ByteString.copyFromUtf8("some-invalid-signature")).build());
            scheduleSign.setSigMap(this.sigMap);
        }

        builder.setScheduleSign(scheduleSign);

        scheduleSignTxn = builder.build();

        given(accessor.getTxn()).willReturn(scheduleSignTxn);
        given(txnCtx.accessor()).willReturn(accessor);
    }
}