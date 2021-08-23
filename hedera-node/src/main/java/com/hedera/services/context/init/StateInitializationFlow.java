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
import com.hedera.services.state.StateAccessor;
import com.hedera.services.state.annotations.WorkingState;
import com.hedera.services.stream.RecordStreamManager;

public class StateInitializationFlow {
	private final StateAccessor stateAccessor;
	private final RecordStreamManager recordStreamManager;

	public StateInitializationFlow(
			@WorkingState StateAccessor stateAccessor,
			RecordStreamManager recordStreamManager
	) {
		this.stateAccessor = stateAccessor;
		this.recordStreamManager = recordStreamManager;
	}

	public void runWith(ServicesState activeState) {
		stateAccessor.updateFrom(activeState);
		final var activeHash = activeState.runningHashLeaf().getRunningHash().getHash();
		recordStreamManager.setInitialHash(activeHash);
	}
}
