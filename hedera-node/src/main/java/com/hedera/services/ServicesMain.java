package com.hedera.services;

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

import com.hedera.services.context.ServicesContext;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleTopic;
import com.hedera.services.context.properties.Profile;
import com.hedera.services.utils.JvmSystemExits;
import com.hedera.services.utils.SystemExits;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleBlobMeta;
import com.hedera.services.state.merkle.MerkleOptionalBlob;
import com.hedera.services.legacy.exception.InvalidTotalAccountBalanceException;
import com.hedera.services.legacy.services.state.initialization.DefaultSystemAccountsCreator;
import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import com.swirlds.common.PlatformStatus;
import com.swirlds.common.SwirldMain;
import com.swirlds.common.SwirldState;
import com.swirlds.common.io.SerializableDataOutputStream;
import com.swirlds.fcmap.FCMap;
import com.swirlds.platform.Browser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.hedera.services.context.SingletonContextsManager.CONTEXTS;
import static com.hedera.services.context.properties.Profile.*;
import static com.swirlds.common.PlatformStatus.*;
import static org.apache.commons.codec.binary.Hex.encodeHexString;

/**
 * Drives the major state transitions for a Hedera Node via its {@link ServicesContext}.
 *
 * @author Michael Tinker
 */
public class ServicesMain implements SwirldMain {
	public static Logger log = LogManager.getLogger(ServicesMain.class);

	static final String FC_DUMP_LOC_TPL = "data/saved/%s/%d/%s-round%d.fcm";

	public static Function<String, SerializableDataOutputStream> foutSupplier = dumpLoc -> {
		try {
			return new SerializableDataOutputStream(Files.newOutputStream(Path.of(dumpLoc)));
		} catch (Exception e) {
			log.warn("Unable to use suggested dump location {}, falling back to STDOUT!", dumpLoc, e);
			return new SerializableDataOutputStream(System.out);
		}
	};

	static final String ISS_ERROR_MSG_PATTERN =
			"In round %d, node %d received a signed state from node %d with " +
					"a signature different than %s on %s [accounts :: %s | storage :: %s | topics :: %s]!";
	static final String ISS_FALLBACK_ERROR_MSG_PATTERN =
			"In round %d, node %s received a signed state from node %s differing from its local "
					+ "signed state; could not provide all details!";
	private static Function<LoggedIssMeta, String> ISS_ERROR_MSG_FN = meta -> String.format(
			ISS_ERROR_MSG_PATTERN,
			meta.round, meta.self, meta.other,
			encodeHexString(meta.sig), encodeHexString(meta.hash),
			encodeHexString(meta.accountsHash), encodeHexString(meta.storageHash), encodeHexString(meta.topicsHash));

	public static final String START_INIT_MSG_PATTERN = "Using context to initialize HederaNode#%d...";

	SystemExits systemExits = new JvmSystemExits();
	Supplier<Charset> defaultCharset = Charset::defaultCharset;
	ServicesContext ctx;

	/**
	 * Convenience launcher for dev env.
	 *
	 * @param args
	 * 		ignored
	 */
	public static void main(String... args) {
		Browser.main(null);
	}

	@Override
	public void init(Platform ignore, NodeId nodeId) {
		if (!StandardCharsets.UTF_8.equals(defaultCharset.get())) {
			log.error("Default charset is {}, not UTF-8! Exiting.", defaultCharset.get());
			systemExits.fail(1);
		}
		try {
			Locale.setDefault(Locale.US);
			ctx = CONTEXTS.lookup(nodeId.getId());
			logInfoWithConsoleEcho(String.format(START_INIT_MSG_PATTERN, ctx.id().getId()));
			contextDrivenInit();
			log.info("init finished.");
		} catch (IllegalStateException ise) {
			log.error("Fatal precondition violated in HederaNode#{}!", ctx.id(), ise);
			systemExits.fail(1);
		}
	}

	@Override
	public ServicesState newState() {
		return new ServicesState();
	}

	@Override
	public void platformStatusChange(PlatformStatus status) {
		log.info("Now current platform status = {} in HederaNode#{}.", status, ctx.id());
		ctx.platformStatus().set(status);
		if (status == ACTIVE) {
			ctx.recordStream().setInFreeze(false);
		} else if (status == MAINTENANCE) {
			ctx.recordStream().setInFreeze(true);
			ctx.updateFeature();
		} else {
			log.info("Platform {} status set to : {}", ctx.id(), status);
		}
	}

	@Override
	public void newSignedState(SwirldState signedState, Instant when, long ignored) {
		if (ctx.properties().getBooleanProperty("hedera.exportBalancesOnNewSignedState") &&
				ctx.balancesExporter().isTimeToExport(when)) {
			try {
				ctx.balancesExporter().toCsvFile((ServicesState) signedState, when);
			} catch (InvalidTotalAccountBalanceException itabe) {
				log.error("HederaNode#{} has invalid total balance in signed state, exiting!", ctx.id(), itabe);
				systemExits.fail(1);
			}
		}
	}

	@Override
	public void run() {
		/* No-op. */
	}

	@Override
	public void preEvent() {
		/* No-op. */
	}

	private void contextDrivenInit() {
		registerIssListener();
		checkPropertySources();
		log.info("Property sources are available.");
		configurePlatform();
		log.info("Platform is configured.");
		migrateStateIfNeeded();
		log.info("Migrations complete.");
		validateLedgerState();
		log.info("Ledger state ok.");
		loadPropertiesAndPermissions();
		log.info("Initialized properties and permissions.");
		startRecordStreamThread();
		log.info("Record stream started.");
		startNettyIfAppropriate();
		log.info("Netty started.");
		createSystemAccountsIfNeeded();
		log.info("System accounts rationalized.");
		createSystemFilesIfNeeded();
		log.info("System files rationalized.");
		exportAccountsIfDesired();
		log.info("Accounts exported.");
		reviewRecordExpirations();
		log.info("Record expiration reviewed.");
		loadFeeSchedule();
		log.info("Fee schedule loaded.");
		sanitizeProperties();
	}

	private void startRecordStreamThread() {
		ctx.recordStreamThread().start();
	}

	private void throwIseOrLogError(IllegalStateException ise) {
		if (ctx.properties().getBooleanProperty("hedera.exitOnNodeStartupFailure")) {
			throw ise;
		} else {
			log.error("Not exiting despite severe error!", ise);
		}
	}

	private void exportAccountsIfDesired() {
		try {
			String path = ctx.properties().getStringProperty("hedera.accountsExportPath");
			ctx.accountsExporter().toFile(ctx.accounts(), path);
		} catch (Exception e) {
			throwIseOrLogError(new IllegalStateException("Could not export accounts!", e));
		}
	}

	private void createSystemFilesIfNeeded() {
		try {
			ctx.systemFilesManager().createAddressBookIfMissing();
			ctx.systemFilesManager().createNodeDetailsIfMissing();
			ctx.systemFilesManager().loadFeeSchedules();
			ctx.systemFilesManager().loadExchangeRates();
		} catch (Exception e) {
			throwIseOrLogError(new IllegalStateException("Could not create system files!", e));
		}
	}

	private void loadPropertiesAndPermissions() {
		try {
			ctx.systemFilesManager().loadApplicationProperties();
			ctx.systemFilesManager().loadApiPermissions();
		} catch (Exception e) {
			throwIseOrLogError(new IllegalStateException("Could not create Config Properties system files!", e));
		}
	}

	private void createSystemAccountsIfNeeded() {
		if (ctx.properties().getBooleanProperty("hedera.createSystemAccountsOnStartup")) {
			try {
				ctx.systemAccountsCreator().createSystemAccounts(ctx.accounts(), ctx.addressBook());
				ctx.pause().forMs(DefaultSystemAccountsCreator.SUGGESTED_POST_CREATION_PAUSE_MS);
			} catch (Exception e) {
				throwIseOrLogError(new IllegalStateException("Could not create system accounts!", e));
			}
		}
	}

	private void startNettyIfAppropriate() {
		int port = ctx.properties().getIntProperty("grpc.port");
		final int PORT_MODULUS = 1000;
		int tlsPort = ctx.properties().getIntProperty("grpc.tlsPort");
		log.info("TLS is turned on by default on node {}", ctx.id());
		Profile activeProfile = ctx.properties().getProfileProperty("hedera.profiles.active");
		log.info("Active profile: {}", activeProfile);
		if (activeProfile == DEV) {
			if (onlyDefaultNodeListens()) {
				if (thisNodeIsDefaultListener()) {
					ctx.grpc().start(port, tlsPort, this::logInfoWithConsoleEcho);
				}
			} else {
				int portOffset = thisNodeIsDefaultListener()
						? 0
						: ctx.addressBook().getAddress(ctx.id().getId()).getPortExternalIpv4() % PORT_MODULUS;
				ctx.grpc().start(port + portOffset, tlsPort + portOffset, this::logInfoWithConsoleEcho);
			}
		} else if (activeProfile == PROD) {
			ctx.grpc().start(port, tlsPort, this::logInfoWithConsoleEcho);
		} else {
			log.warn("No Netty config for profile {}, skipping gRPC startup!", activeProfile);
		}
	}

	private boolean onlyDefaultNodeListens() {
		return ctx.properties().getBooleanProperty("dev.onlyDefaultNodeListens");
	}

	private boolean thisNodeIsDefaultListener() {
		String myNodeAccount = ctx.addressBook().getAddress(ctx.id().getId()).getMemo();
		String blessedNodeAccount = ctx.properties().getStringProperty("dev.defaultListeningNodeAccount");
		return myNodeAccount.equals(blessedNodeAccount);
	}

	private void sanitizeProperties() {
		ctx.propertySanitizer().sanitize(ctx.propertySources());
	}

	private void loadFeeSchedule() {
		ctx.fees().init();
	}

	private void checkPropertySources() {
		ctx.propertySources().assertSourcesArePresent();
	}

	private void configurePlatform() {
		ctx.platform().setSleepAfterSync(0L);
	}

	private void migrateStateIfNeeded() {
		ctx.stateMigrations().runAllFor(ctx);
	}

	private void validateLedgerState() {
		ctx.ledgerValidator().assertIdsAreValid(ctx.accounts());
		if (!ctx.ledgerValidator().hasExpectedTotalBalance(ctx.accounts())) {
			log.warn("Unexpected total balance in ledger, nodeId={}!", ctx.id());
		}
		if (ctx.nodeAccount() == null) {
			throwIseOrLogError(new IllegalStateException("Unknown ledger account!"));
		}
	}

	private void reviewRecordExpirations() {
		long consensusTimeOfLastHandledTxn =
				Optional.ofNullable(ctx.consensusTimeOfLastHandledTxn()).map(Instant::getEpochSecond).orElse(0L);
		ctx.recordsHistorian().reviewExistingRecords(consensusTimeOfLastHandledTxn);
	}

	void logInfoWithConsoleEcho(String s) {
		log.info(s);
		if (ctx.consoleOut() != null) {
			ctx.consoleOut().println(s);
		}
	}

	void registerIssListener() {
		ctx.platform().addSignedStateListener(
				(platform, book, swirldState, events, self, other, round, consensusTime, numConsEvents, sig, hash) -> {
					try {
						ServicesState state = (ServicesState) swirldState;
						LoggedIssMeta meta = new LoggedIssMeta(
								round, self.getId(), other.getId(),
								sig, hash,
								state.accounts().getRootHash().getValue(),
								state.storage().getRootHash().getValue(),
								state.topics().getRootHash().getValue());

						ctx.issEventInfo().alert(consensusTime);
						if (ctx.issEventInfo().shouldDumpThisRound()) {
							log.error(ISS_ERROR_MSG_FN.apply(meta));
							dumpFcms(
									self.getId(), round, state.accounts(),
									state.storage(), state.topics()
							);
						}
					} catch (Exception e) {
						String fallbackMsg = String.format(
								ISS_FALLBACK_ERROR_MSG_PATTERN, 1, String.valueOf(self), String.valueOf(other));
						log.error(fallbackMsg, e);
					}
				});
	}

	public static void dumpFcms(
			long nodeId,
			long round,
			FCMap<MerkleEntityId, MerkleAccount> accounts,
			FCMap<MerkleBlobMeta, MerkleOptionalBlob> storage,
			FCMap<MerkleEntityId, MerkleTopic> topics
	) throws IOException {
		var accountsFout = foutSupplier.apply(
				String.format(FC_DUMP_LOC_TPL,
						ServicesMain.class.getName(), nodeId, "accounts", round));
		accounts.copyTo(accountsFout);
		accounts.copyToExtra(accountsFout);
		accountsFout.close();

		var storageFout = foutSupplier.apply(
				String.format(FC_DUMP_LOC_TPL,
						ServicesMain.class.getName(), nodeId, "storage", round));
		storage.copyTo(storageFout);
		storage.copyToExtra(storageFout);
		storageFout.close();

		var topicsFout = foutSupplier.apply(
				String.format(FC_DUMP_LOC_TPL,
						ServicesMain.class.getName(), nodeId, "topics", round));
		topics.copyTo(topicsFout);
		topics.copyToExtra(topicsFout);
		topicsFout.close();
	}

	public static class LoggedIssMeta {
		final long round, self, other;
		final byte[] sig, hash, accountsHash, storageHash, topicsHash;

		public LoggedIssMeta(
				long round, long self, long other,
				byte[] sig, byte[] hash, byte[] accountsHash, byte[] storageHash, byte[] topicsHash
		) {
			this.self = self;
			this.other = other;
			this.round = round;
			this.sig = sig;
			this.hash = hash;
			this.storageHash = storageHash;
			this.accountsHash = accountsHash;
			this.topicsHash = topicsHash;
		}
	}
}
