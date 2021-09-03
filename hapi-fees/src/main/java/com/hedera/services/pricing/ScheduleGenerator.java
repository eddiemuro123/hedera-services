package com.hedera.services.pricing;

/*-
 * ‌
 * Hedera Services API Fees
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.SubType;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusSubmitMessage;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileAppend;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAccountWipe;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenBurn;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenFeeScheduleUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenMint;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static java.time.Month.SEPTEMBER;

public class ScheduleGenerator {
	private static final String FEE_SCHEDULE_FEES_KEY = "fees";
	private static final String FEE_SCHEDULE_TYPE_KEY = "subType";
	private static final String FEE_SCHEDULE_ENTRY_KEY = "transactionFeeSchedule";
	private static final String FEE_SCHEDULE_FUNCTION_KEY = "hederaFunctionality";

	private final Instant CURRENT_SCHEDULE_EXPIRY =
			LocalDateTime.of(2021, SEPTEMBER, 2, 0, 0)
					.plusMonths(1)
					.toInstant(ZoneOffset.UTC);
	private final Instant NEXT_SCHEDULE_EXPIRY =
			LocalDateTime.of(2021, SEPTEMBER, 2, 0, 0)
					.plusMonths(2)
					.toInstant(ZoneOffset.UTC);

	private final FeeSchedules feeSchedules = new FeeSchedules();

	String feeSchedulesFor(List<Pair<HederaFunctionality, List<SubType>>> data) throws IOException {
		final List<Map<String, Object>> currentFeeSchedules = new ArrayList<>();
		final List<Map<String, Object>> nextFeeSchedules = new ArrayList<>();

		for (var datum : data) {
			final var function = datum.getKey();
			final var subTypes = datum.getValue();
			final var tfs = pricesAsTfs(function, subTypes);
			currentFeeSchedules.add(tfs);
			nextFeeSchedules.add(tfs);
		}
		currentFeeSchedules.add(Map.of("expiryTime", CURRENT_SCHEDULE_EXPIRY.getEpochSecond()));
		nextFeeSchedules.add(Map.of("expiryTime", NEXT_SCHEDULE_EXPIRY.getEpochSecond()));

		final List<Map<String, Object>> everything = List.of(
				Map.of("currentFeeSchedule", currentFeeSchedules),
				Map.of("nextFeeSchedule", nextFeeSchedules));
		return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(everything);
	}

	private Map<String, Object> pricesAsTfs(HederaFunctionality function, List<SubType> subTypes) throws IOException {
		final Map<String, Object> transactionFeeSchedule = new HashMap<>();

		final Map<String, Object> details = new LinkedHashMap<>();
		details.put(FEE_SCHEDULE_FUNCTION_KEY, function.toString());

		final List<Map<String, Object>> allTypedPrices = new ArrayList<>();
		for (var subType : subTypes) {
			final Map<String, Object> typedPrices = new LinkedHashMap<>();
			typedPrices.put(FEE_SCHEDULE_TYPE_KEY, subType.toString());

			final Map<ResourceProvider, Map<UsableResource, Long>> prices =
					feeSchedules.canonicalPricesFor(function, subType);
			for (var provider : ResourceProvider.class.getEnumConstants()) {
				final Map<String, Long> constrainedPrices = new LinkedHashMap<>();
				final var providerPrices = prices.get(provider);
				for (var resource : UsableResource.class.getEnumConstants()) {
					final var price = providerPrices.get(resource);
					constrainedPrices.put(resource.toString().toLowerCase(), price);
				}
				constrainedPrices.put("min", 0L);
				constrainedPrices.put("max", 1000000000000000L);
				typedPrices.put(provider.jsonKey(), constrainedPrices);
			}

			allTypedPrices.add(typedPrices);
		}
		details.put(FEE_SCHEDULE_FEES_KEY, allTypedPrices);
		transactionFeeSchedule.put(FEE_SCHEDULE_ENTRY_KEY, details);
		return transactionFeeSchedule;
	}

	@SuppressWarnings("unchecked")
	static final List<Pair<HederaFunctionality, List<SubType>>> SUPPORTED_FUNCTIONS = List.of(new Pair[] {
					/* Crypto */
					Pair.of(CryptoTransfer, List.of(
							DEFAULT,
							TOKEN_FUNGIBLE_COMMON,
							TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
							TOKEN_NON_FUNGIBLE_UNIQUE,
							TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES
					)),
					/* File */
					Pair.of(FileAppend, List.of(DEFAULT)),
					/* Token */
					Pair.of(TokenCreate, List.of(
							TOKEN_FUNGIBLE_COMMON,
							TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
							TOKEN_NON_FUNGIBLE_UNIQUE,
							TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES
					)),
					Pair.of(TokenMint, List.of(TOKEN_NON_FUNGIBLE_UNIQUE)),
					Pair.of(TokenBurn, List.of(TOKEN_NON_FUNGIBLE_UNIQUE)),
					Pair.of(TokenAccountWipe, List.of(TOKEN_NON_FUNGIBLE_UNIQUE)),
					Pair.of(TokenFeeScheduleUpdate, List.of(DEFAULT)),
					/* Consensus */
					Pair.of(ConsensusSubmitMessage, List.of(DEFAULT)),
			}
	);
}
