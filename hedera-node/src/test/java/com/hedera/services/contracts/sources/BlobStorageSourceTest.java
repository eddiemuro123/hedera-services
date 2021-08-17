package com.hedera.services.contracts.sources;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;

import com.hedera.services.utils.EntityIdUtils;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlobStorageSourceTest {
  Map<byte[], byte[]> blobDelegate;

  byte[] address = EntityIdUtils.asSolidityAddress(0, 0, 13257);
  byte[] storage = "STORAGE".getBytes();

  BlobStorageSource subject;

  @BeforeEach
  private void setup() {
    blobDelegate = mock(Map.class);

    subject = new BlobStorageSource(blobDelegate);
  }

  @Test
  void unsupportedOpsThrow() {
    // expect:
    assertThrows(UnsupportedOperationException.class, () -> subject.reset());
    assertThrows(UnsupportedOperationException.class, () -> subject.prefixLookup(new byte[0], 0));
    assertThrows(UnsupportedOperationException.class, () -> subject.init(null));
  }

  @Test
  void noopsSanityCheck() {
    // expect:
    assertFalse(subject.flush());
    assertFalse(subject.isAlive());
    assertDoesNotThrow(() -> subject.init());
    assertDoesNotThrow(() -> subject.close());
    assertDoesNotThrow(() -> subject.updateBatch(null));
    assertThrows(RuntimeException.class, () -> subject.keys());
  }

  @Test
  void nameSetterWorks() {
    // when:
    subject.setName("testing123");

    // then:
    assertEquals("testing123", subject.getName());
  }

  @Test
  void delegatesGetOfMissing() {
    given(blobDelegate.get(any())).willReturn(null);

    // expect:
    assertNull(subject.get(address));
    // and:
    verify(blobDelegate).get(argThat((byte[] bytes) -> Arrays.equals(address, bytes)));
  }

  @Test
  void delegatesGetOfPresent() {
    given(blobDelegate.get(any())).willReturn(storage);

    // when:
    byte[] stuff = subject.get(address);

    // expect:
    assertArrayEquals(storage, stuff);
    // and:
    verify(blobDelegate).get(argThat((byte[] bytes) -> Arrays.equals(address, bytes)));
  }

  @Test
  void delegatesPut() {
    // when:
    subject.put(address, storage);

    // then:
    verify(blobDelegate)
        .put(
            argThat((byte[] bytes) -> Arrays.equals(address, bytes)),
            argThat((byte[] bytes) -> Arrays.equals(storage, bytes)));
  }

  @Test
  void delegatesDelete() {
    // when:
    subject.delete(address);

    // then:
    verify(blobDelegate).remove(argThat((byte[] bytes) -> Arrays.equals(address, bytes)));
  }
}
