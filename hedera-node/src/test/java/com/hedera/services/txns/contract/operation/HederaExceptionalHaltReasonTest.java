package com.hedera.services.txns.contract.operation;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class HederaExceptionalHaltReasonTest {
    HederaExceptionalHaltReason subject;

    @BeforeEach
    void setUp() {
        subject = new HederaExceptionalHaltReason();
    }

    @Test
    void instance() {
        assertEquals("Invalid account reference", HederaExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS.getDescription());
        assertEquals("Self destruct to the same address", HederaExceptionalHaltReason.SELF_DESTRUCT_TO_SELF.getDescription());
        assertEquals("Invalid signature", HederaExceptionalHaltReason.INVALID_SIGNATURE.getDescription());
    }
}
