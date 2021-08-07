package com.hedera.services.context;

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

import com.hedera.services.context.properties.PropertySources;
import com.hedera.services.exceptions.ContextNotFoundException;
import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.hedera.services.context.SingletonContextsManager.CONTEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;

class SingletonContextsManagerTest {
	private final NodeId id = new NodeId(false, 1L);

	Platform platform;
	ServicesContext ctx;
	PropertySources propertySources;

	@BeforeEach
	private void resetContexts() {
		CONTEXTS.clear();
		ctx = mock(ServicesContext.class);
		given(ctx.id()).willReturn(id);
		platform = mock(Platform.class);
		propertySources = mock(PropertySources.class);
	}

	@Test
	void failsFastOnMissingContext() {
		// expect:
		assertThrows(ContextNotFoundException.class, () -> CONTEXTS.lookup(1L));
	}

	@Test
	void createsExpectedContext() {
		// given:
		assertFalse(CONTEXTS.isInitialized(1L));

		// when:
		CONTEXTS.store(ctx);

		// then:
		assertEquals(ctx, CONTEXTS.lookup(1L));
		// and:
		assertTrue(CONTEXTS.isInitialized(1L));
	}
}
