package com.hedera.services.throttling;

import com.hedera.services.throttling.bootstrap.ThrottleDefinitions;
import com.hedera.services.throttling.real.DeterministicThrottle;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.function.IntSupplier;

public class DeterministicThrottling implements TimedFunctionalityThrottling {
	private static final Logger log = LogManager.getLogger(DeterministicThrottling.class);

	private final IntSupplier capacitySplitSource;

	List<DeterministicThrottle> activeThrottles = Collections.emptyList();
	EnumMap<HederaFunctionality, ThrottleReqsManager> functionReqs = new EnumMap<>(HederaFunctionality.class);

	public DeterministicThrottling(IntSupplier capacitySplitSource) {
		this.capacitySplitSource = capacitySplitSource;
	}

	@Override
	public boolean shouldThrottle(HederaFunctionality function) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean shouldThrottle(HederaFunctionality function, Instant now) {
		ThrottleReqsManager manager;
		if ((manager = functionReqs.get(function)) == null) {
			throw new IllegalStateException("No throttle present for (apparently supported) operation " + function + "!");
		}
		return !manager.allReqsMetAt(now);
	}

	@Override
	public List<DeterministicThrottle.UsageSnapshot> currentUsageFor(HederaFunctionality function) {
		ThrottleReqsManager manager;
		if ((manager = functionReqs.get(function)) == null) {
			throw new IllegalStateException("No throttle present for (apparently supported) operation " + function + "!");
		}
		return manager.currentUsage();
	}

	@Override
	public List<DeterministicThrottle> allActiveThrottles() {
		return activeThrottles;
	}

	@Override
	public void rebuildFor(ThrottleDefinitions defs) {
		List<DeterministicThrottle> newActiveThrottles = new ArrayList<>();
		EnumMap<HederaFunctionality, List<Pair<DeterministicThrottle, Integer>>> reqLists
				= new EnumMap<>(HederaFunctionality.class);

		int n = capacitySplitSource.getAsInt();
		for (var bucket : defs.getBuckets()) {
			var mapping = bucket.asThrottleMapping(n);
			var throttle = mapping.getLeft();
			var reqs = mapping.getRight();
			for (var req : reqs) {
				reqLists.computeIfAbsent(req.getLeft(), ignore -> new ArrayList<>())
						.add(Pair.of(throttle, req.getRight()));
			}
			newActiveThrottles.add(throttle);
		}
		EnumMap<HederaFunctionality, ThrottleReqsManager> newFunctionReqs = new EnumMap<>(HederaFunctionality.class);
		reqLists.forEach((function, reqs) -> newFunctionReqs.put(function, new ThrottleReqsManager(reqs)));

		functionReqs = newFunctionReqs;
		activeThrottles = newActiveThrottles;
	}
}
