package com.hedera.services.state.forensics;

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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class HashLogger {
	private static final Logger log = LogManager.getLogger(HashLogger.class);

	@Inject
	public HashLogger() {
	}

	public void logHashesFor(ServicesState state) {
		log.info("[SwirldState Hashes]\n" +
						"  Overall                :: {}\n" +
						"  Accounts               :: {}\n" +
						"  Storage                :: {}\n" +
						"  Topics                 :: {}\n" +
						"  Tokens                 :: {}\n" +
						"  TokenAssociations      :: {}\n" +
						"  DiskFs                 :: {}\n" +
						"  ScheduledTxs           :: {}\n" +
						"  NetworkContext         :: {}\n" +
						"  AddressBook            :: {}\n" +
						"  RecordsRunningHashLeaf :: {}\n" +
						"    ↪ Running hash       :: {}\n" +
						"  UniqueTokens           :: {}\n",
				state.getHash(),
				state.accounts().getHash(),
				state.storage().getHash(),
				state.topics().getHash(),
				state.tokens().getHash(),
				state.tokenAssociations().getHash(),
				state.specialFiles().getHash(),
				state.scheduleTxs().getHash(),
				state.networkCtx().getHash(),
				state.addressBook().getHash(),
				state.runningHashLeaf().getHash(),
				state.runningHashLeaf().getRunningHash().getHash(),
				state.uniqueTokens().getHash());
	}
}
