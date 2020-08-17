package com.hedera.services.utils;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
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

import com.hedera.services.legacy.services.stats.HederaNodeStats;

import java.util.TimerTask;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class StatsDumpTimerTask extends TimerTask {
	public static Logger log = LogManager.getLogger(StatsDumpTimerTask.class);

	private HederaNodeStats stats;

	@Override
	public void run() {
		log.info("Dumping stats...");
		stats.dumpHederaNodeStats();
	}

	public StatsDumpTimerTask(HederaNodeStats stats) {
		this.stats = stats;
	}

}
