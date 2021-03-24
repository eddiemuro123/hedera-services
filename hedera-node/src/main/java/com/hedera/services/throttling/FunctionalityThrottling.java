package com.hedera.services.throttling;

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

import com.hedera.services.throttling.real.DeterministicThrottle;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Map;

public interface FunctionalityThrottling {
	boolean shouldThrottle(HederaFunctionality function);

	default List<PretendDetThrottle.StateSnapshot> throttleStatesFor(HederaFunctionality function) {
		throw new AssertionError("Not implemented!");
	}

	/* The key will be a name given in the system throttles file 0.0.123 */
	default Map<String, PretendDetThrottle> currentThrottles() {
		throw new AssertionError("Not implemented!");
	}

	default List<DeterministicThrottle> activeThrottles() {
		throw new AssertionError("Not implemented!");
	}
}
