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

import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.hedera.test.extensions.LoggingTarget;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;
import com.hederahashgraph.api.proto.java.Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.Set;

import static com.hedera.services.context.properties.ScreenedSysFileProps.DEPRECATED_PROP_TPL;
import static com.hedera.services.context.properties.ScreenedSysFileProps.MISPLACED_PROP_TPL;
import static com.hedera.services.context.properties.ScreenedSysFileProps.UNPARSEABLE_PROP_TPL;
import static com.hedera.services.context.properties.ScreenedSysFileProps.UNTRANSFORMABLE_PROP_TPL;
import static com.hedera.services.context.properties.ScreenedSysFileProps.UNUSABLE_PROP_TPL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(LogCaptureExtension.class)
class ScreenedSysFilePropsTest {
	@LoggingTarget
	private LogCaptor logCaptor;

	@LoggingSubject
	private ScreenedSysFileProps subject;

	@BeforeEach
	void setup() {
		subject = new ScreenedSysFileProps();
	}

	@Test
	void delegationWorks() {
		subject.from121 = Map.of("tokens.maxPerAccount", 42);

		assertEquals(Set.of("tokens.maxPerAccount"), subject.allPropertyNames());
		assertEquals(42, subject.getProperty("tokens.maxPerAccount"));
		assertTrue(subject.containsProperty("tokens.maxPerAccount"));
		assertFalse(subject.containsProperty("nonsense"));
	}

	@Test
	void ignoresNonGlobalDynamic() {
		subject.screenNew(withJust("notGlobalDynamic", "42"));

		assertTrue(subject.from121.isEmpty());
		assertThat(logCaptor.warnLogs(), contains(
				String.format(MISPLACED_PROP_TPL, "notGlobalDynamic")));
	}

	@Test
	void incorporatesStandardGlobalDynamic() {
		final var oldMap = subject.from121;

		subject.screenNew(withJust("tokens.maxPerAccount", "42"));

		assertEquals(Map.of("tokens.maxPerAccount", 42), subject.from121);
		assertNotSame(oldMap, subject.from121);
	}

	@Test
	void incorporatesLegacyGlobalDynamic() {
		subject.screenNew(withJust("configAccountNum", "42"));

		assertEquals(1, subject.from121.size());
		assertEquals(42L, subject.from121.get("ledger.maxAccountNum"));
		assertThat(logCaptor.warnLogs(), contains(
				String.format(DEPRECATED_PROP_TPL, "configAccountNum", "ledger.maxAccountNum")));
	}

	@Test
	void incorporatesLegacyGlobalDynamicWithTransform() {
		subject.screenNew(withJust("defaultFeeCollectionAccount", "0.0.98"));

		assertEquals(1, subject.from121.size());
		assertEquals(98L, subject.from121.get("ledger.fundingAccount"));
		assertThat(logCaptor.warnLogs(), contains(
				String.format(DEPRECATED_PROP_TPL, "defaultFeeCollectionAccount", "ledger.fundingAccount")));
	}

	@ParameterizedTest
	@CsvSource({
			"ABC, tokens.maxPerAccount, false, NumberFormatException",
			"CryptoCreate;CryptoTransfer;Oops, scheduling.whitelist, false, IllegalArgumentException",
			"CryptoCreate;CryptoTransfer;CryptoGetAccountBalance, scheduling.whitelist, true,",
			(MerkleToken.UPPER_BOUND_TOKEN_NAME_UTF8_BYTES + 1) + ", tokens.maxTokenNameUtf8Bytes, true,",
			"1, ledger.transfers.maxLen, true,",
			"1, ledger.tokenTransfers.maxLen, true,",
			(MerkleToken.UPPER_BOUND_SYMBOL_UTF8_BYTES + 1) + ", tokens.maxSymbolUtf8Bytes, true,",
			"-1, rates.intradayChangeLimitPercent, true,"
	})
	void warnsOfUnusableOrUnparseable(
			String unsupported,
			final String prop,
			final boolean isUnusable,
			String exception
	) {
		unsupported = unsupported.replaceAll(";", ",");
		final var expectedLog = isUnusable
				? String.format(UNUSABLE_PROP_TPL, unsupported, prop)
				: String.format(UNPARSEABLE_PROP_TPL, unsupported, prop, exception);

		subject.screenNew(withJust(prop, unsupported));

		assertTrue(subject.from121.isEmpty());
		assertThat(logCaptor.warnLogs(), contains(expectedLog));
	}

	@Test
	void warnsOfUntransformableGlobalDynamic() {
		subject.screenNew(withJust("defaultFeeCollectionAccount", "abc"));

		assertTrue(subject.from121.isEmpty());
		assertThat(logCaptor.warnLogs(), contains(
				"Property name 'defaultFeeCollectionAccount' is deprecated, please use 'ledger.fundingAccount' " +
						"instead!",
				String.format(
						UNTRANSFORMABLE_PROP_TPL, "abc", "defaultFeeCollectionAccount", "IllegalArgumentException"),
				"Property 'defaultFeeCollectionAccount' is not global/dynamic, please find it a proper home!"));
	}

	private static final ServicesConfigurationList withJust(final String name, final String value) {
		return ServicesConfigurationList.newBuilder()
				.addNameValue(from(name, value))
				.build();
	}

	private static final Setting from(final String name, final String value) {
		return Setting.newBuilder().setName(name).setValue(value).build();
	}
}
