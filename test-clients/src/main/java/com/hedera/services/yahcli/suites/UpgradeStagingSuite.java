package com.hedera.services.yahcli.suites;


/*-
 * ‌
 * Hedera Services Test Clients
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

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.hedera.services.bdd.spec.utilops.UtilVerbs.prepareUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.telemetryUpgrade;

public class UpgradeStagingSuite extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(UpgradeStagingSuite.class);

	private final byte[] upgradeFileHash;
	private final String upgradeFile;
	/* Only non-null for a TELEMETRY_UPGRADE */
	private final Instant startTime;
	private final Map<String, String> specConfig;

	public UpgradeStagingSuite(
			final Map<String, String> specConfig,
			final byte[] upgradeFileHash,
			final String upgradeFile
	) {
		this(specConfig, upgradeFileHash, upgradeFile, null);
	}

	public UpgradeStagingSuite(
			final Map<String, String> specConfig,
			final byte[] upgradeFileHash,
			final String upgradeFile,
			@Nullable Instant startTime
	) {
		this.specConfig = specConfig;
		this.upgradeFile = upgradeFile;
		this.upgradeFileHash = upgradeFileHash;
		this.startTime = startTime;
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[] {
				doStagingAction()
		});
	}

	private HapiApiSpec doStagingAction() {
		final HapiSpecOperation op;

		if (startTime == null) {
			op = prepareUpgrade()
					.withUpdateFile(upgradeFile)
					.havingHash(upgradeFileHash);
		} else {
			op = telemetryUpgrade()
					.startingAt(startTime)
					.withUpdateFile(upgradeFile)
					.havingHash(upgradeFileHash);
		}

		return HapiApiSpec.customHapiSpec("DoStagingAction").withProperties(specConfig).given().when().then(op);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
