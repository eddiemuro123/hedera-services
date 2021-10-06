package com.hedera.services.txns.contract.process;

/*
 * -
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 *
 */

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static com.hedera.services.txns.contract.operation.HederaExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class HederaTracerTest {
    private HederaTracer subject;

    @Mock
    private MessageFrame mf;

    @Mock
    private OperationTracer.ExecuteOperation eo;

    @BeforeEach
    void setUp() {
        subject = new HederaTracer();
    }

    @Test
    void traceExecution() {
        subject.traceExecution(mf, eo);
        verify(eo).execute();
    }

    @Test
    void traceAccountCreationResult() {
        var haltReason = Optional.of(INVALID_SOLIDITY_ADDRESS);
        subject.traceAccountCreationResult(mf, haltReason);
        verify(mf).setExceptionalHaltReason(haltReason);
    }
}
