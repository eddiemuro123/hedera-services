package com.hedera.services;

/*-
 * ‌
 * Hedera Services Node
 *
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 *
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

import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import com.swirlds.common.PlatformStatus;
import com.swirlds.common.SwirldMain;
import com.swirlds.common.SwirldState;
import com.swirlds.common.notification.listeners.ReconnectCompleteListener;
import com.swirlds.platform.Browser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;

import static com.hedera.services.context.AppsManager.APPS;
import static com.swirlds.common.PlatformStatus.ACTIVE;
import static com.swirlds.common.PlatformStatus.MAINTENANCE;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Implements callbacks to bind gRPC services, react to platform status changes,
 * and incorporate new signed states.
 */
public class ServicesMain implements SwirldMain {
	private static final Logger log = LogManager.getLogger(ServicesMain.class);

	private ServicesApp app;

	/**
	 * Convenience launcher for dev env.
	 *
	 * @param args ignored
	 */
	public static void main(String... args) {
		Browser.main(null);
	}

	@Override
	public void init(Platform ignore, NodeId nodeId) {
		try {
			app = APPS.get(nodeId.getId());
			initApp();
		} catch (IllegalArgumentException iae) {
			log.error("No app present for {}", nodeId, iae);
			throw new AssertionError("Cannot continue without an app");
		}
	}

	@Override
	public void platformStatusChange(PlatformStatus status) {
		final var nodeId = app.nodeId();
		log.info("Now current platform status = {} in HederaNode#{}.", status, nodeId);
		app.platformStatus().set(status);
		if (status == ACTIVE) {
			app.recordStreamManager().setInFreeze(false);
		} else if (status == MAINTENANCE) {
			app.recordStreamManager().setInFreeze(true);
			final var os = System.getProperty("os.name").toLowerCase();
			app.updateHelper().runIfAppropriateOn(os);
		} else {
			log.info("Platform {} status set to : {}", nodeId, status);
		}
	}

	@Override
	public void newSignedState(SwirldState signedState, Instant consensusTime, long round) {
		final var servicesState = (ServicesState) signedState;

		if (app.platformStatus().get() == MAINTENANCE) {
			servicesState.logSummary();
		}

		final var balancesExporter = app.balancesExporter();
		if (balancesExporter.isTimeToExport(consensusTime)) {
			balancesExporter.exportBalancesFrom(servicesState, consensusTime, app.nodeId());
		}
	}

	@Override
	public ServicesState newState() {
		return new ServicesState();
	}

	@Override
	public void run() {
		/* No-op. */
	}

	@Override
	public void preEvent() {
		/* No-op. */
	}

	private void initApp() {
		if (defaultCharsetIsCorrect() && sha384DigestIsAvailable()) {
			try {
				Locale.setDefault(Locale.US);
				consoleLog(String.format("Using context to initialize HederaNode#%s...", app.nodeId()));
				doStagedInit();
			} catch (Exception e) {
				log.error("Fatal precondition violated in HederaNode#{}", app.nodeId(), e);
				app.systemExits().fail(1);
			}
		} else {
			app.systemExits().fail(1);
		}
	}

	private void doStagedInit() {
		initSystemFiles();
		log.info("System files rationalized");

		createSystemAccountsIfNeeded();
		log.info("System accounts initialized");

		validateLedgerState();
		log.info("Ledger state ok");

		configurePlatform();
		log.info("Platform is configured w/ callbacks and stats registered");

		exportAccountsIfDesired();
		log.info("Accounts exported (if requested)");

		startNettyIfAppropriate();
		log.info("Netty started (if appropriate)");

		startCreateStateIfNeeded();
	}

	private void exportAccountsIfDesired() {
		app.accountsExporter().toFile(app.workingState().accounts());
	}

	private void initSystemFiles() {
		final var sysFilesManager = app.sysFilesManager();
		sysFilesManager.createAddressBookIfMissing();
		sysFilesManager.createNodeDetailsIfMissing();
		sysFilesManager.createUpdateZipFileIfMissing();
		app.networkCtxManager().loadObservableSysFilesIfNeeded();
	}

	private void createSystemAccountsIfNeeded() {
		app.sysAccountsCreator().ensureSystemAccounts(app.backingAccounts(), app.workingState().addressBook());
	}

	private void startNettyIfAppropriate() {
		app.grpcStarter().startIfAppropriate();
	}

	private void configurePlatform() {
		final var platform = app.platform();
		platform.setSleepAfterSync(0L);
		platform.addSignedStateListener(app.issListener());
		app.statsManager().initializeFor(platform);
	}

	private void validateLedgerState() {
		app.ledgerValidator().validate(app.workingState().accounts());
		app.nodeInfo().validateSelfAccountIfStaked();
		app.notificationEngine().get().register(ReconnectCompleteListener.class, app.reconnectListener());
	}

	private boolean defaultCharsetIsCorrect() {
		final var charset = app.nativeCharset().get();
		if (!UTF_8.equals(charset)) {
			log.error("Default charset is {}, not UTF-8", charset);
			return false;
		}
		return true;
	}

	private void startCreateStateIfNeeded() {
		app.createState().startIfNeeded();
	}

	private boolean sha384DigestIsAvailable() {
		try {
			app.digestFactory().forName("SHA-384");
			return true;
		} catch (NoSuchAlgorithmException nsae) {
			log.error(nsae);
			return false;
		}
	}

	private void consoleLog(String s) {
		log.info(s);
		app.consoleOut().ifPresent(c -> c.println(s));
	}
}
