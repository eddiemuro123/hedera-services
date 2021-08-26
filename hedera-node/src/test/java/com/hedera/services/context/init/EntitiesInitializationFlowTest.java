package com.hedera.services.context.init;

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

import com.hedera.services.state.expiry.ExpiryManager;
import com.hedera.services.state.logic.NetworkCtxManager;
import com.swirlds.blob.BinaryObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;


@ExtendWith(MockitoExtension.class)
class EntitiesInitializationFlowTest {
	@Mock
	private ExpiryManager expiryManager;
	@Mock
	private NetworkCtxManager networkCtxManager;
	@Mock
	private BinaryObjectStore binaryObjectStore;

	private EntitiesInitializationFlow subject;

	@BeforeEach
	void setUp() {
		subject = new EntitiesInitializationFlow(expiryManager, networkCtxManager, () -> binaryObjectStore);
	}

	@Test
	void runsAsExpectedWhenStoreNotInitializing() {
		// when:
		subject.run();

		// then:
		verify(expiryManager).reviewExistingPayerRecords();
		verify(expiryManager).reviewExistingShortLivedEntities();
		verify(networkCtxManager).setObservableFilesNotLoaded();
		verify(networkCtxManager).loadObservableSysFilesIfNeeded();
	}

	@Test
	void runsAsExpectedWhenStoreInitializing() {
		given(binaryObjectStore.isInitializing()).willReturn(true);

		// when:
		subject.run();

		// then:
		verify(expiryManager).reviewExistingPayerRecords();
		verify(expiryManager).reviewExistingShortLivedEntities();
		verify(networkCtxManager).setObservableFilesNotLoaded();
		verify(networkCtxManager, never()).loadObservableSysFilesIfNeeded();
	}
}
