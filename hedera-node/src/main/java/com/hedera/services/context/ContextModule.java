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

import com.hederahashgraph.api.proto.java.AccountID;
import com.swirlds.blob.BinaryObjectStore;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;
import java.time.Instant;
import java.util.function.Supplier;

@Module
public abstract class ContextModule {
	@Binds
	@Singleton
	public abstract CurrentPlatformStatus bindCurrentPlatformStatus(ContextPlatformStatus contextPlatformStatus);

	@Binds
	@Singleton
	public abstract TransactionContext bindTransactionContext(BasicTransactionContext txnCtx);

	@Provides
	@Singleton
	public static Supplier<Instant> provideConsensusTime(TransactionContext txnCtx) {
		return txnCtx::consensusTime;
	}

	@Provides
	@Singleton
	public static AccountID provideEffectiveNodeAccount(NodeInfo nodeInfo) {
		return nodeInfo.hasSelfAccount() ? nodeInfo.selfAccount() : AccountID.getDefaultInstance();
	}

	@Provides
	@Singleton
	public static Supplier<BinaryObjectStore> provideBinaryObjectStore() {
		return BinaryObjectStore::getInstance;
	}
}
