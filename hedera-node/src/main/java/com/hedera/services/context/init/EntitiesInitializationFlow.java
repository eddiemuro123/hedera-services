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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.function.Supplier;

@Singleton
public class EntitiesInitializationFlow {
	private static final Logger log = LogManager.getLogger(EntitiesInitializationFlow.class);

	private final ExpiryManager expiries;
	private final NetworkCtxManager networkCtxManager;
	private final Supplier<BinaryObjectStore> binaryObjectStore;

	@Inject
	public EntitiesInitializationFlow(
			ExpiryManager expiries,
			NetworkCtxManager networkCtxManager,
			Supplier<BinaryObjectStore> binaryObjectStore
	) {
		this.expiries = expiries;
		this.networkCtxManager = networkCtxManager;
		this.binaryObjectStore = binaryObjectStore;
	}

	public void run() {
		expiries.reviewExistingPayerRecords();
		log.info("Payer records reviewed");
		/* Use any entities stored in state to rebuild queue of expired entities. */
		expiries.reviewExistingShortLivedEntities();
		log.info("Short-lived entities reviewed");

		/* Re-initialize the "observable" system files; that is, the files which have
	 	associated callbacks managed by the SysFilesCallback object. We explicitly
	 	re-mark the files are not loaded here, in case this is a reconnect. (During a
	 	reconnect the blob store might still be reloading, and we will finish loading
	 	the observable files in the ServicesMain.init method.) */
		networkCtxManager.setObservableFilesNotLoaded();
		if (!binaryObjectStore.get().isInitializing()) {
			log.info("Blob store is ready, loading observable system files");
			networkCtxManager.loadObservableSysFilesIfNeeded();
			log.info("Finished loading observable system files");
		}
	}
}
