package com.hedera.services.txns.network;

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.state.merkle.MerkleNetworkContext;
import com.hedera.services.state.merkle.MerkleSpecialFiles;
import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.hedera.test.extensions.LoggingTarget;
import com.hedera.test.utils.IdUtils;
import com.swirlds.platform.state.DualStateImpl;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static com.hedera.services.txns.network.UpgradeActions.EXEC_IMMEDIATE_MARKER;
import static com.hedera.services.txns.network.UpgradeActions.EXEC_TELEMETRY_MARKER;
import static com.hedera.services.txns.network.UpgradeActions.FREEZE_ABORTED_MARKER;
import static com.hedera.services.txns.network.UpgradeActions.FREEZE_SCHEDULED_MARKER;
import static com.hedera.services.txns.network.UpgradeActions.NOW_FROZEN_MARKER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith({ MockitoExtension.class, LogCaptureExtension.class })
class UpgradeActionsTest {
	private static final Instant then = Instant.ofEpochSecond(1_234_567L, 890);
	private static final String markerFilesLoc = "src/test/resources/upgrade";
	private static final String nonexistentMarkerFilesLoc = "src/test/resources/edargpu";
	private static final byte[] PRETEND_ARCHIVE =
			"This is missing something. Hard to put a finger on what...".getBytes(StandardCharsets.UTF_8);

	@Mock
	private GlobalDynamicProperties dynamicProperties;
	@Mock
	private DualStateImpl dualState;
	@Mock
	private UpgradeActions.UnzipAction unzipAction;
	@Mock
	private MerkleSpecialFiles specialFiles;
	@Mock
	private MerkleNetworkContext networkCtx;

	@LoggingTarget
	private LogCaptor logCaptor;
	@LoggingSubject
	private UpgradeActions subject;

	@BeforeEach
	void setUp() {
		subject = new UpgradeActions(
				unzipAction, dynamicProperties, () -> dualState, () -> specialFiles, () -> networkCtx);
	}

	@AfterAll
	static void cleanup() {
		List.of(
				EXEC_IMMEDIATE_MARKER,
				FREEZE_ABORTED_MARKER,
				FREEZE_SCHEDULED_MARKER,
				NOW_FROZEN_MARKER).forEach(UpgradeActionsTest::rmIfPresent);
	}

	@Test
	void complainsLoudlyIfUpgradeHashDoesntMatch() {
		rmIfPresent(EXEC_IMMEDIATE_MARKER);

		given(networkCtx.hasPreparedUpgrade()).willReturn(true);
		given(networkCtx.getPreparedUpdateFileNum()).willReturn(150L);
		given(dynamicProperties.upgradeArtifactsLoc()).willReturn(markerFilesLoc);

		subject.catchUpOnMissedSideEffects();

		assertThat(
				logCaptor.errorLogs(),
				contains(
						Matchers.startsWith("Cannot redo NMT upgrade prep, file 0.0.150 changed since FREEZE_UPGRADE"),
						Matchers.equalTo("Manual remediation may be necessary to avoid node ISS")));
		assertFalse(
				Paths.get(markerFilesLoc, EXEC_IMMEDIATE_MARKER).toFile().exists(),
				"Should not create " + EXEC_IMMEDIATE_MARKER + " if prepared file hash doesn't match");
	}

	@Test
	void catchesUpOnUpgradePreparationIfInContext() throws IOException {
		rmIfPresent(EXEC_IMMEDIATE_MARKER);

		given(networkCtx.hasPreparedUpgrade()).willReturn(true);
		given(networkCtx.isPreparedFileHashValidGiven(specialFiles)).willReturn(true);
		given(networkCtx.getPreparedUpdateFileNum()).willReturn(150L);
		given(specialFiles.get(IdUtils.asFile("0.0.150"))).willReturn(PRETEND_ARCHIVE);
		given(dynamicProperties.upgradeArtifactsLoc()).willReturn(markerFilesLoc);
		given(dualState.getFreezeTime()).willReturn(then);
		given(dualState.getLastFrozenTime()).willReturn(then);

		subject.catchUpOnMissedSideEffects();

		verify(unzipAction).unzip(PRETEND_ARCHIVE, markerFilesLoc);
		assertMarkerCreated(EXEC_IMMEDIATE_MARKER, null);
	}

	@Test
	void catchUpIsNoopWithNothingToDo() {
		rmIfPresent(FREEZE_SCHEDULED_MARKER);
		rmIfPresent(EXEC_IMMEDIATE_MARKER);

		subject.catchUpOnMissedSideEffects();

		assertFalse(
				Paths.get(markerFilesLoc, EXEC_IMMEDIATE_MARKER).toFile().exists(),
				"Should not create " + EXEC_IMMEDIATE_MARKER + " if no prepared upgrade in state");
		assertFalse(
				Paths.get(markerFilesLoc, FREEZE_SCHEDULED_MARKER).toFile().exists(),
				"Should not create " + FREEZE_SCHEDULED_MARKER + " if dual freeze time is null");
	}

	@Test
	void doesntCatchUpOnFreezeScheduleIfInDualAndNoUpgradeIsPrepared() {
		rmIfPresent(FREEZE_SCHEDULED_MARKER);

		given(dualState.getFreezeTime()).willReturn(then);

		subject.catchUpOnMissedSideEffects();

		assertFalse(
				Paths.get(markerFilesLoc, FREEZE_SCHEDULED_MARKER).toFile().exists(),
				"Should not create " + FREEZE_SCHEDULED_MARKER + " if no upgrade is prepared");
	}

	@Test
	void catchesUpOnFreezeScheduleIfInDualAndUpgradeIsPrepared() throws IOException {
		rmIfPresent(FREEZE_SCHEDULED_MARKER);

		given(dualState.getFreezeTime()).willReturn(then);
		given(networkCtx.hasPreparedUpgrade()).willReturn(true);
		given(dynamicProperties.upgradeArtifactsLoc()).willReturn(markerFilesLoc);

		subject.catchUpOnMissedSideEffects();

		assertMarkerCreated(FREEZE_SCHEDULED_MARKER, then);
	}

	@Test
	void freezeCatchUpClearsDualAndWritesNoMarkersIfJustUnfrozen() {
		rmIfPresent(FREEZE_ABORTED_MARKER);
		rmIfPresent(FREEZE_SCHEDULED_MARKER);

		given(dualState.getFreezeTime()).willReturn(then);
		given(dualState.getLastFrozenTime()).willReturn(then);

		subject.catchUpOnMissedSideEffects();

		assertFalse(
				Paths.get(markerFilesLoc, FREEZE_ABORTED_MARKER).toFile().exists(),
				"Should not create " + FREEZE_ABORTED_MARKER + " if dual last frozen time is freeze time");
		assertFalse(
				Paths.get(markerFilesLoc, FREEZE_SCHEDULED_MARKER).toFile().exists(),
				"Should not create " + FREEZE_SCHEDULED_MARKER + " if dual last frozen time is freeze time");
		verify(dualState).setFreezeTime(null);
	}

	@Test
	void catchesUpOnFreezeAbortIfNullInDual() throws IOException {
		rmIfPresent(FREEZE_ABORTED_MARKER);

		given(dynamicProperties.upgradeArtifactsLoc()).willReturn(markerFilesLoc);

		subject.catchUpOnMissedSideEffects();

		assertMarkerCreated(FREEZE_ABORTED_MARKER, null);
	}

	@Test
	void complainsLoudlyWhenUnableToUnzipArchive() throws IOException {
		rmIfPresent(EXEC_IMMEDIATE_MARKER);

		given(dynamicProperties.upgradeArtifactsLoc()).willReturn(markerFilesLoc);
		willThrow(IOException.class).given(unzipAction).unzip(PRETEND_ARCHIVE, markerFilesLoc);

		subject.extractSoftwareUpgrade(PRETEND_ARCHIVE).join();

		assertThat(
				logCaptor.errorLogs(),
				contains(
						Matchers.startsWith("Failed to unzip archive for NMT consumption java.io.IOException: "),
						Matchers.equalTo("Manual remediation may be necessary to avoid node ISS")));
		assertFalse(
				Paths.get(markerFilesLoc, EXEC_IMMEDIATE_MARKER).toFile().exists(),
				"Should not create " + EXEC_IMMEDIATE_MARKER + " if unzip failed");
	}

	@Test
	void preparesForUpgrade() throws IOException {
		rmIfPresent(EXEC_IMMEDIATE_MARKER);

		given(dynamicProperties.upgradeArtifactsLoc()).willReturn(markerFilesLoc);

		subject.extractSoftwareUpgrade(PRETEND_ARCHIVE).join();

		verify(unzipAction).unzip(PRETEND_ARCHIVE, markerFilesLoc);
		assertMarkerCreated(EXEC_IMMEDIATE_MARKER, null);
	}

	@Test
	void upgradesTelemetry() throws IOException {
		rmIfPresent(EXEC_TELEMETRY_MARKER);

		given(dynamicProperties.upgradeArtifactsLoc()).willReturn(markerFilesLoc);

		subject.extractTelemetryUpgrade(PRETEND_ARCHIVE, then).join();

		verify(unzipAction).unzip(PRETEND_ARCHIVE, markerFilesLoc);
		assertMarkerCreated(EXEC_TELEMETRY_MARKER, then);
	}

	@Test
	void externalizesFreeze() throws IOException {
		rmIfPresent(NOW_FROZEN_MARKER);

		given(dynamicProperties.upgradeArtifactsLoc()).willReturn(markerFilesLoc);

		subject.externalizeFreeze();

		assertMarkerCreated(NOW_FROZEN_MARKER, null);
	}

	@Test
	void setsExpectedFreezeAndWritesMarkerForFreezeUpgrade() throws IOException {
		rmIfPresent(FREEZE_SCHEDULED_MARKER);

		given(dynamicProperties.upgradeArtifactsLoc()).willReturn(markerFilesLoc);

		subject.scheduleFreezeUpgradeAt(then);

		verify(dualState).setFreezeTime(then);

		assertMarkerCreated(FREEZE_SCHEDULED_MARKER, then);
	}

	@Test
	void setsExpectedFreezeOnlyForFreezeOnly() {
		rmIfPresent(FREEZE_SCHEDULED_MARKER);

		subject.scheduleFreezeOnlyAt(then);

		verify(dualState).setFreezeTime(then);

		assertFalse(
				Paths.get(markerFilesLoc, FREEZE_SCHEDULED_MARKER).toFile().exists(),
				"Should not create " + FREEZE_SCHEDULED_MARKER + " for FREEZE_ONLY");
	}

	@Test
	void nullsOutDualOnAborting() throws IOException {
		rmIfPresent(FREEZE_ABORTED_MARKER);

		given(dynamicProperties.upgradeArtifactsLoc()).willReturn(markerFilesLoc);

		subject.abortScheduledFreeze();

		verify(dualState).setFreezeTime(null);

		assertMarkerCreated(FREEZE_ABORTED_MARKER, null);
	}

	@Test
	void complainsLoudlyWhenUnableToWriteMarker() {
		given(dynamicProperties.upgradeArtifactsLoc()).willReturn(nonexistentMarkerFilesLoc);
		final var p = Paths.get(nonexistentMarkerFilesLoc, FREEZE_ABORTED_MARKER);

		subject.abortScheduledFreeze();

		verify(dualState).setFreezeTime(null);

		assertThat(
				logCaptor.errorLogs(),
				contains(
						Matchers.startsWith("Failed to write NMT marker " + p),
						Matchers.equalTo("Manual remediation may be necessary to avoid node ISS")));
	}

	@Test
	void determinesIfFreezeIsScheduled() {
		assertFalse(subject.isFreezeScheduled());

		given(dualState.getFreezeTime()).willReturn(then);

		assertTrue(subject.isFreezeScheduled());
	}

	private static void rmIfPresent(String file) {
		final var p = Paths.get(markerFilesLoc, file);
		final var f = p.toFile();
		if (f.exists()) {
			f.delete();
		}
	}

	private void assertMarkerCreated(final String file, final @Nullable Instant when) throws IOException {
		final var p = Paths.get(markerFilesLoc, file);
		final var f = p.toFile();
		assertTrue(f.exists(), file + " should have been created, but wasn't");
		final var contents = Files.readString(p);
		f.delete();
		if (file.equals(EXEC_IMMEDIATE_MARKER)) {
			assertThat(
					logCaptor.infoLogs(),
					contains(
							Matchers.equalTo(
									"About to unzip 58 bytes for software update into " + markerFilesLoc),
							Matchers.equalTo(
									"Finished unzipping 58 bytes for software update into " + markerFilesLoc),
							Matchers.equalTo("Wrote marker " + p)));
		} else if (file.equals(EXEC_TELEMETRY_MARKER)) {
			assertThat(
					logCaptor.infoLogs(),
					contains(
							Matchers.equalTo(
									"About to unzip 58 bytes for telemetry update into " + markerFilesLoc),
							Matchers.equalTo(
									"Finished unzipping 58 bytes for telemetry update into " + markerFilesLoc),
							Matchers.equalTo("Wrote marker " + p)));
		} else {
			assertThat(
					logCaptor.infoLogs(),
					contains(Matchers.equalTo("Wrote marker " + p)));
		}
		if (when != null) {
			final var writtenEpochSecond = Long.parseLong(contents);
			assertEquals(when.getEpochSecond(), writtenEpochSecond);
		} else {
			assertEquals(UpgradeActions.MARK, contents);
		}
	}
}