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

import com.hedera.services.ServicesState;
import com.hedera.services.config.HederaNumbers;
import com.hedera.services.config.MockHederaNumbers;
import com.hedera.services.files.FileUpdateInterceptor;
import com.hedera.services.files.HederaFs;
import com.hedera.services.state.StateAccessor;
import com.hedera.services.stream.RecordStreamManager;
import com.hedera.services.stream.RecordsRunningHashLeaf;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.crypto.RunningHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.function.Consumer;

import static com.hedera.services.context.properties.StaticPropertiesHolder.STATIC_PROPERTIES;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StateInitializationFlowTest {
	private final HederaNumbers defaultNumbers = new MockHederaNumbers();

	@Mock
	private Hash hash;
	@Mock
	private HederaFs hfs;
	@Mock
	private RunningHash runningHash;
	@Mock
	private ServicesState activeState;
	@Mock
	private RecordsRunningHashLeaf runningHashLeaf;
	@Mock
	private StateAccessor stateAccessor;
	@Mock
	private RecordStreamManager recordStreamManager;
	@Mock
	private FileUpdateInterceptor aFileInterceptor;
	@Mock
	private FileUpdateInterceptor bFileInterceptor;
	@Mock
	private Consumer<HederaNumbers> staticNumbersHolder;

	private StateInitializationFlow subject;

	@BeforeEach
	void setUp() {
		subject = new StateInitializationFlow(
				hfs,
				defaultNumbers,
				recordStreamManager,
				stateAccessor,
				Set.of(aFileInterceptor, bFileInterceptor));
	}

	@Test
	void performsAsExpectedWithNoInterceptorsRegistered() {
		setupMockNumInitialization();

		given(runningHash.getHash()).willReturn(hash);
		given(runningHashLeaf.getRunningHash()).willReturn(runningHash);
		given(activeState.runningHashLeaf()).willReturn(runningHashLeaf);

		// when:
		subject.runWith(activeState);

		// then:
		verify(stateAccessor).updateFrom(activeState);
		verify(recordStreamManager).setInitialHash(hash);
		verify(hfs).register(aFileInterceptor);
		verify(hfs).register(bFileInterceptor);
		verify(staticNumbersHolder).accept(defaultNumbers);

		cleanupMockNumInitialization();
	}

	@Test
	void performsAsExpectedWithInterceptorsRegistered() {
		setupMockNumInitialization();

		given(runningHash.getHash()).willReturn(hash);
		given(runningHashLeaf.getRunningHash()).willReturn(runningHash);
		given(activeState.runningHashLeaf()).willReturn(runningHashLeaf);
		given(hfs.numRegisteredInterceptors()).willReturn(5);

		// when:
		subject.runWith(activeState);

		// then:
		verify(stateAccessor).updateFrom(activeState);
		verify(recordStreamManager).setInitialHash(hash);
		verify(hfs, never()).register(any());
		verify(staticNumbersHolder).accept(defaultNumbers);

		cleanupMockNumInitialization();
	}

	private void setupMockNumInitialization() {
		StateInitializationFlow.setStaticNumbersHolder(staticNumbersHolder);
	}

	private void cleanupMockNumInitialization() {
		StateInitializationFlow.setStaticNumbersHolder(STATIC_PROPERTIES::setNumbersFrom);
	}
}
