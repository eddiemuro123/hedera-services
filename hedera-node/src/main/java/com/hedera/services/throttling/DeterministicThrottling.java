package com.hedera.services.throttling;

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

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.sysfiles.domain.throttling.ThrottleDefinitions;
import com.hedera.services.throttles.DeterministicThrottle;
import com.hedera.services.throttles.GasLimitDeterministicThrottle;
import com.hedera.services.utils.TxnAccessor;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.TokenMintTransactionBody;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.function.IntSupplier;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCall;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenMint;

public class DeterministicThrottling implements TimedFunctionalityThrottling {
	private static final Logger log = LogManager.getLogger(DeterministicThrottling.class);

	private final IntSupplier capacitySplitSource;
	private final GlobalDynamicProperties dynamicProperties;

	private List<DeterministicThrottle> activeThrottles = Collections.emptyList();
	private EnumMap<HederaFunctionality, ThrottleReqsManager> functionReqs = new EnumMap<>(HederaFunctionality.class);

	private GasLimitDeterministicThrottle frontEndGasThrottle;
	private GasLimitDeterministicThrottle backEndGasThrottle;

	public DeterministicThrottling(
			IntSupplier capacitySplitSource,
			GlobalDynamicProperties dynamicProperties
	) {
		this.capacitySplitSource = capacitySplitSource;
		this.dynamicProperties = dynamicProperties;
	}

	@Override
	public boolean shouldThrottleTxn(TxnAccessor accessor, boolean frontEndThrottle) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean shouldThrottleQuery(HederaFunctionality queryFunction) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean shouldThrottleTxn(TxnAccessor accessor, Instant now, boolean frontEndThrottle) {
		final var function = accessor.getFunction();
		ThrottleReqsManager manager;

		if ((function == ContractCreate || function == ContractCall) && dynamicProperties.shouldThrottleByGas()) {
			final var txGasLimit = function == ContractCreate ?
					accessor.getTxn().getContractCreateInstance().getGas() : accessor.getTxn().getContractCall().getGas();
			if(frontEndThrottle) {
				if (frontEndGasThrottle == null || !frontEndGasThrottle.allow(now, txGasLimit)) {
					return true;
				}
			} else {
				if (backEndGasThrottle == null || !backEndGasThrottle.allow(now, txGasLimit)) {
					return true;
				}
			}
		}

		if ((manager = functionReqs.get(function)) == null) {
			return true;
		} else if (function == TokenMint) {
			return shouldThrottleMint(manager, accessor.getTxn().getTokenMint(), now);
		} else {
			return !manager.allReqsMetAt(now);
		}
	}

	public void leakUnusedGasPreviouslyReserved(long value) {
		backEndGasThrottle.leakUnusedGasPreviouslyReserved(value);
	}

	@Override
	public boolean shouldThrottleQuery(HederaFunctionality queryFunction, Instant now) {
		ThrottleReqsManager manager;
		if ((manager = functionReqs.get(queryFunction)) == null) {
			return true;
		}
		return !manager.allReqsMetAt(now);
	}

	@Override
	public List<DeterministicThrottle> allActiveThrottles() {
		return activeThrottles;
	}

	@Override
	public List<DeterministicThrottle> activeThrottlesFor(HederaFunctionality function) {
		ThrottleReqsManager manager;
		if ((manager = functionReqs.get(function)) == null) {
			return Collections.emptyList();
		}
		return manager.managedThrottles();
	}

	@Override
	public void rebuildFor(ThrottleDefinitions defs) {
		List<DeterministicThrottle> newActiveThrottles = new ArrayList<>();
		EnumMap<HederaFunctionality, List<Pair<DeterministicThrottle, Integer>>> reqLists
				= new EnumMap<>(HederaFunctionality.class);

		int n = capacitySplitSource.getAsInt();
		for (var bucket : defs.getBuckets()) {
			try {
				var mapping = bucket.asThrottleMapping(n);
				var throttle = mapping.getLeft();
				var reqs = mapping.getRight();
				for (var req : reqs) {
					reqLists.computeIfAbsent(req.getLeft(), ignore -> new ArrayList<>())
							.add(Pair.of(throttle, req.getRight()));
				}
				newActiveThrottles.add(throttle);
			} catch (IllegalStateException badBucket) {
				log.error("When constructing bucket '{}' from state: {}", bucket.getName(), badBucket.getMessage());
			}
		}
		EnumMap<HederaFunctionality, ThrottleReqsManager> newFunctionReqs = new EnumMap<>(HederaFunctionality.class);
		reqLists.forEach((function, reqs) -> newFunctionReqs.put(function, new ThrottleReqsManager(reqs)));

		functionReqs = newFunctionReqs;
		activeThrottles = newActiveThrottles;

		if(dynamicProperties.shouldThrottleByGas()) {
			if(defs.getTotalAllowedGasPerSec() == 0) {
				log.error("ThrottleByGas global dynamic property is set to true but totalAllowedGasPerSec is not set in throttles.json or is set to 0.");
			} else {
				frontEndGasThrottle = new GasLimitDeterministicThrottle(defs.getTotalAllowedGasPerSec());
				backEndGasThrottle = new GasLimitDeterministicThrottle(defs.getTotalAllowedGasPerSec());
			}
		}

		logResolvedDefinitions();
	}

	private void logResolvedDefinitions() {
		int n = capacitySplitSource.getAsInt();
		var sb = new StringBuilder("Resolved throttles (after splitting capacity " + n + " ways) - \n");
		functionReqs.entrySet().stream()
				.sorted(Comparator.comparing(entry -> entry.getKey().toString()))
				.forEach(entry -> {
					var function = entry.getKey();
					var manager = entry.getValue();
					sb.append("  ").append(function).append(": ")
							.append(manager.asReadableRequirements())
							.append("\n");
				});
		sb.append("  ")
				.append("ThrottleByGasLimit: ")
				.append(frontEndGasThrottle == null ? 0 : frontEndGasThrottle.getCapacity())
				.append(" throttleByGas ")
				.append(dynamicProperties.shouldThrottleByGas())
				.append("\n");
		log.info(sb.toString().trim());
	}

	void setFunctionReqs(EnumMap<HederaFunctionality, ThrottleReqsManager> functionReqs) {
		this.functionReqs = functionReqs;
	}

	private boolean shouldThrottleMint(ThrottleReqsManager manager, TokenMintTransactionBody op, Instant now) {
		final var numNfts = op.getMetadataCount();
		if (numNfts == 0) {
			return !manager.allReqsMetAt(now);
		} else {
			return !manager.allReqsMetAt(now, numNfts, dynamicProperties.nftMintScaleFactor());
		}
	}
}
