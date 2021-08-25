package com.hedera.services;

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

import com.hedera.services.context.CurrentPlatformStatus;
import com.hedera.services.context.NodeInfo;
import com.hedera.services.grpc.GrpcStarter;
import com.hedera.services.ledger.accounts.BackingStore;
import com.hedera.services.state.StateAccessor;
import com.hedera.services.state.exports.AccountsExporter;
import com.hedera.services.state.exports.BalancesExporter;
import com.hedera.services.state.initialization.SystemAccountsCreator;
import com.hedera.services.state.initialization.SystemFilesManager;
import com.hedera.services.state.logic.NetworkCtxManager;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.validation.LedgerValidator;
import com.hedera.services.stats.ServicesStatsManager;
import com.hedera.services.stream.RecordStreamManager;
import com.hedera.services.txns.network.UpdateHelper;
import com.hedera.services.utils.NamedDigestFactory;
import com.hedera.services.utils.SystemExits;
import com.hederahashgraph.api.proto.java.AccountID;
import com.swirlds.common.AddressBook;
import com.swirlds.common.InvalidSignedStateListener;
import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.notification.listeners.ReconnectCompleteListener;
import com.swirlds.fcmap.FCMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static com.hedera.services.context.AppsManager.APPS;
import static com.swirlds.common.PlatformStatus.ACTIVE;
import static com.swirlds.common.PlatformStatus.MAINTENANCE;
import static com.swirlds.common.PlatformStatus.STARTING_UP;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ServicesMainTest {
	private final Instant consensusNow = Instant.ofEpochSecond(1_234_567L, 890);
	private final long round = 1_234_567L;
	private final long selfId = 123L;
	private final long unselfId = 666L;
	private final NodeId nodeId = new NodeId(false, selfId);
	private final NodeId edonId = new NodeId(false, unselfId);

	@Mock
	private Platform platform;
	@Mock
	private SystemExits systemExits;
	@Mock
	private PrintStream consoleOut;
	@Mock
	private Supplier<Charset> nativeCharset;
	@Mock
	private ServicesApp app;
	@Mock
	private NamedDigestFactory namedDigestFactory;
	@Mock
	private SystemFilesManager systemFilesManager;
	@Mock
	private NetworkCtxManager networkCtxManager;
	@Mock
	private StateAccessor stateAccessor;
	@Mock
	private AddressBook book;
	@Mock
	private BackingStore<AccountID, MerkleAccount> backingAccounts;
	@Mock
	private SystemAccountsCreator systemAccountsCreator;
	@Mock
	private FCMap<MerkleEntityId, MerkleAccount> accounts;
	@Mock
	private LedgerValidator ledgerValidator;
	@Mock
	private NodeInfo nodeInfo;
	@Mock
	private ReconnectCompleteListener reconnectListener;
	@Mock
	private InvalidSignedStateListener issListener;
	@Mock
	private NotificationEngine notificationEngine;
	@Mock
	private ServicesStatsManager statsManager;
	@Mock
	private AccountsExporter accountsExporter;
	@Mock
	private GrpcStarter grpcStarter;
	@Mock
	private CurrentPlatformStatus currentPlatformStatus;
	@Mock
	private RecordStreamManager recordStreamManager;
	@Mock
	private UpdateHelper updateHelper;
	@Mock
	private ServicesState signedState;
	@Mock
	private BalancesExporter balancesExporter;

	private ServicesMain subject = new ServicesMain();

	@Test
	void throwsErrorOnMissingApp() {
		// expect:
		Assertions.assertThrows(AssertionError.class, () -> subject.init(platform, edonId));
	}

	@Test
	void failsOnWrongNativeCharset() {
		withDoomedApp();

		given(nativeCharset.get()).willReturn(StandardCharsets.US_ASCII);

		// when:
		subject.init(platform, nodeId);

		// then:
		verify(systemExits).fail(1);
	}

	@Test
	void failsOnUnavailableDigest() throws NoSuchAlgorithmException {
		withDoomedApp();

		given(nativeCharset.get()).willReturn(UTF_8);
		given(namedDigestFactory.forName("SHA-384")).willThrow(NoSuchAlgorithmException.class);
		given(app.digestFactory()).willReturn(namedDigestFactory);

		// when:
		subject.init(platform, nodeId);

		// then:
		verify(systemExits).fail(1);
	}

	@Test
	void doesAppDrivenInit() throws NoSuchAlgorithmException {
		withRunnableApp();

		// when:
		subject.init(platform, nodeId);

		// then:
		verify(systemFilesManager).createAddressBookIfMissing();
		verify(systemFilesManager).createNodeDetailsIfMissing();
		verify(systemFilesManager).createUpdateZipFileIfMissing();
		verify(networkCtxManager).loadObservableSysFilesIfNeeded();
		// and:
		verify(systemAccountsCreator).ensureSystemAccounts(backingAccounts, book);
		// and:
		verify(ledgerValidator).validate(accounts);
		verify(nodeInfo).validateSelfAccountIfStaked();
		// and:
		verify(platform).setSleepAfterSync(0L);
		verify(platform).addSignedStateListener(issListener);
		verify(statsManager).initializeFor(platform);
		verify(accountsExporter).toFile(accounts);
		verify(notificationEngine).register(ReconnectCompleteListener.class, reconnectListener);
		verify(grpcStarter).startIfAppropriate();
	}

	@Test
	void noopsAsExpected() {
		// expect:
		Assertions.assertDoesNotThrow(subject::run);
		Assertions.assertDoesNotThrow(subject::preEvent);
	}

	@Test
	void createsNewState() {
		// expect:
		assertThat(subject.newState(), instanceOf(ServicesState.class));
	}

	@Test
	void updatesCurrentMiscPlatformStatus() throws NoSuchAlgorithmException {
		withRunnableApp();
		withChangeableApp();

		// given:
		subject.init(platform, nodeId);

		// when:
		subject.platformStatusChange(STARTING_UP);

		// then:
		verify(currentPlatformStatus).set(STARTING_UP);
	}

	@Test
	void updatesCurrentActivePlatformStatus() throws NoSuchAlgorithmException {
		withRunnableApp();
		withChangeableApp();

		given(app.recordStreamManager()).willReturn(recordStreamManager);
		// and:
		subject.init(platform, nodeId);

		// when:
		subject.platformStatusChange(ACTIVE);

		// then:
		verify(currentPlatformStatus).set(ACTIVE);
		verify(recordStreamManager).setInFreeze(false);
	}

	@Test
	void updatesCurrentMaintenancePlatformStatus() throws NoSuchAlgorithmException {
		// setup:
		final var os = System.getProperty("os.name").toLowerCase();

		withRunnableApp();
		withChangeableApp();

		given(app.updateHelper()).willReturn(updateHelper);
		given(app.recordStreamManager()).willReturn(recordStreamManager);
		// and:
		subject.init(platform, nodeId);

		// when:
		subject.platformStatusChange(MAINTENANCE);

		// then:
		verify(currentPlatformStatus).set(MAINTENANCE);
		verify(recordStreamManager).setInFreeze(true);
		verify(updateHelper).runIfAppropriateOn(os);
	}

	@Test
	void justLogsIfMaintenanceAndNotTimeToExport() throws NoSuchAlgorithmException {
		withRunnableApp();

		given(app.platformStatus()).willReturn(currentPlatformStatus);
		given(app.balancesExporter()).willReturn(balancesExporter);
		given(currentPlatformStatus.get()).willReturn(MAINTENANCE);
		// and:
		subject.init(platform, nodeId);

		// when:
		subject.newSignedState(signedState, consensusNow, round);

		// then:
		verify(signedState).logSummary();
	}

	@Test
	void exportsIfTime() throws NoSuchAlgorithmException {
		withRunnableApp();

		given(app.platformStatus()).willReturn(currentPlatformStatus);
		given(app.balancesExporter()).willReturn(balancesExporter);
		given(app.nodeId()).willReturn(nodeId);
		given(balancesExporter.isTimeToExport(consensusNow)).willReturn(true);
		given(currentPlatformStatus.get()).willReturn(ACTIVE);
		// and:
		subject.init(platform, nodeId);

		// when:
		subject.newSignedState(signedState, consensusNow, round);

		// then:
		verify(balancesExporter).exportBalancesFrom(signedState, consensusNow, nodeId);
	}

	@Test
	void failsHardIfCannotInit() throws NoSuchAlgorithmException {
		withFailingApp();

		// when:
		subject.init(platform, nodeId);

		// then:
		verify(systemExits).fail(1);
	}

	private void withDoomedApp() {
		APPS.save(selfId, app);
		given(app.nativeCharset()).willReturn(nativeCharset);
		given(app.systemExits()).willReturn(systemExits);
	}

	private void withFailingApp() throws NoSuchAlgorithmException {
		APPS.save(selfId, app);
		given(nativeCharset.get()).willReturn(UTF_8);
		given(namedDigestFactory.forName("SHA-384")).willReturn(null);
		given(app.nativeCharset()).willReturn(nativeCharset);
		given(app.digestFactory()).willReturn(namedDigestFactory);
		given(app.sysFilesManager()).willReturn(systemFilesManager);
		given(app.systemExits()).willReturn(systemExits);
		willThrow(IllegalStateException.class).given(systemFilesManager).createAddressBookIfMissing();
	}

	private void withRunnableApp() throws NoSuchAlgorithmException {
		APPS.save(selfId, app);
		given(nativeCharset.get()).willReturn(UTF_8);
		given(namedDigestFactory.forName("SHA-384")).willReturn(null);
		given(app.nativeCharset()).willReturn(nativeCharset);
		given(app.digestFactory()).willReturn(namedDigestFactory);
		given(app.sysFilesManager()).willReturn(systemFilesManager);
		given(app.networkCtxManager()).willReturn(networkCtxManager);
		given(app.consoleOut()).willReturn(Optional.of(consoleOut));
		given(app.workingState()).willReturn(stateAccessor);
		given(stateAccessor.addressBook()).willReturn(book);
		given(app.workingState()).willReturn(stateAccessor);
		given(app.backingAccounts()).willReturn(backingAccounts);
		given(app.sysAccountsCreator()).willReturn(systemAccountsCreator);
		given(stateAccessor.accounts()).willReturn(accounts);
		given(app.ledgerValidator()).willReturn(ledgerValidator);
		given(app.nodeInfo()).willReturn(nodeInfo);
		given(app.platform()).willReturn(platform);
		given(app.issListener()).willReturn(issListener);
		given(app.notificationEngine()).willReturn(() -> notificationEngine);
		given(app.reconnectListener()).willReturn(reconnectListener);
		given(app.statsManager()).willReturn(statsManager);
		given(app.accountsExporter()).willReturn(accountsExporter);
		given(app.grpcStarter()).willReturn(grpcStarter);
	}

	private void withChangeableApp() {
		given(app.platformStatus()).willReturn(currentPlatformStatus);
		given(app.nodeId()).willReturn(nodeId);
	}
}
