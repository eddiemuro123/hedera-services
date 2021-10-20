package com.hedera.services.context.properties;

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

import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.hedera.test.extensions.LoggingTarget;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(LogCaptureExtension.class)
class SemanticVersionsTest {
	private static final SemanticVersion FROZEN_PROTO_SEMVER = baseSemVer()
			.setPre("zeta.123")
			.setBuild("2b26be40")
			.build();
	private static final SemanticVersion FROZEN_SERVICES_SEMVER = baseSemVer(4, 2, 1)
			.setPre("alpha.0.1.0")
			.setBuild("04eb62b2")
			.build();

	@LoggingTarget
	private LogCaptor logCaptor;

	@LoggingSubject
	private SemanticVersions subject;

	@BeforeEach
	void setUp() {
		subject = new SemanticVersions();
	}

	@Test
	void canParseFullSemver() {
		final var literal = "1.2.4-alpha.1+2b26be40";
		final var expected = baseSemVer()
				.setPre("alpha.1")
				.setBuild("2b26be40")
				.build();

		final var actual = subject.asSemVer(literal);

		assertEquals(expected, actual);
	}

	@Test
	void canParseWithoutBuildMeta() {
		final var literal = "1.2.4-alpha.1";
		final var expected = baseSemVer()
				.setPre("alpha.1")
				.build();

		final var actual = subject.asSemVer(literal);

		assertEquals(expected, actual);
	}

	@Test
	void canParseWithoutJustReqFields() {
		final var literal = "1.2.4";
		final var expected = baseSemVer().build();

		final var actual = subject.asSemVer(literal);

		assertEquals(expected, actual);
	}

	@Test
	void canParseWithoutJustBuildMeta() {
		final var literal = "1.2.4+2b26be40";
		final var expected = baseSemVer()
				.setBuild("2b26be40")
				.build();

		final var actual = subject.asSemVer(literal);

		assertEquals(expected, actual);
	}

	@Test
	void throwsIaeWithInvalidLiteral() {
		final var literal = "1.2..4+2b26be40";

		assertThrows(IllegalArgumentException.class, () -> subject.asSemVer(literal));
	}

	@Test
	void recognizesAvailableResourceAndDoesItOnlyOnce() {
		subject.setVersionInfoResource("frozen-semantic-version.properties");

		final var versions = subject.getDeployed();

		assertEquals(FROZEN_PROTO_SEMVER, versions.protoSemVer());
		assertEquals(FROZEN_SERVICES_SEMVER, versions.hederaSemVer());

		subject.setVersionInfoResource("willNotBeLoadedAsActiveVersionsAreAlreadyAvailable.properties");
		final var sameVersions = subject.getDeployed();

		assertSame(versions, sameVersions);
	}

	@Test
	void warnsOfUnavailableSemversAndUsesEmpty() {
		final var shouldBeEmpty = subject.fromResource("nonExistent.properties", "w/e", "n/a");
		final var desiredPrefix = "Failed to parse resource 'nonExistent.properties' (keys 'w/e' and 'n/a'). " +
				"Version info will be unavailable!";

		assertEquals(SemanticVersion.getDefaultInstance(), shouldBeEmpty.hederaSemVer());
		assertEquals(SemanticVersion.getDefaultInstance(), shouldBeEmpty.protoSemVer());
		assertThat(logCaptor.warnLogs(), contains(Matchers.startsWith(desiredPrefix)));
	}

	private static final SemanticVersion.Builder baseSemVer() {
		return baseSemVer(1, 2, 4);
	}

	private static final SemanticVersion.Builder baseSemVer(final int major, final int minor, final int patch) {
		return SemanticVersion.newBuilder()
				.setMajor(major)
				.setMinor(minor)
				.setPatch(patch);
	}
}
