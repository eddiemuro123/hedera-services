package com.hedera.services.throttling.bootstrap;

import com.hedera.services.throttling.real.BucketThrottle;
import com.hedera.services.throttling.real.DeterministicThrottle;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.toList;

public class ThrottleBucket {
	int burstPeriod;
	String name;
	List<ThrottleGroup> throttleGroups = new ArrayList<>();

	public int getBurstPeriod() {
		return burstPeriod;
	}

	public void setBurstPeriod(int burstPeriod) {
		this.burstPeriod = burstPeriod;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public List<ThrottleGroup> getThrottleGroups() {
		return throttleGroups;
	}

	public void setThrottleGroups(List<ThrottleGroup> throttleGroups) {
		this.throttleGroups = throttleGroups;
	}

	public static ThrottleBucket fromProto(com.hederahashgraph.api.proto.java.ThrottleBucket bucket) {
		var pojo = new ThrottleBucket();
		pojo.name = bucket.getName();
		pojo.burstPeriod = bucket.getBurstPeriod();
		pojo.throttleGroups.addAll(bucket.getThrottleGroupsList().stream()
				.map(ThrottleGroup::fromProto)
				.collect(toList()));
		return pojo;
	}

	public com.hederahashgraph.api.proto.java.ThrottleBucket toProto() {
		return com.hederahashgraph.api.proto.java.ThrottleBucket.newBuilder()
				.setName(name)
				.setBurstPeriod(burstPeriod)
				.addAllThrottleGroups(throttleGroups.stream()
						.map(ThrottleGroup::toProto)
						.collect(toList()))
				.build();
	}

	/**
	 * Returns a deterministic throttle scoped to 1/nth of the nominal opsPerSec
	 * in each throttle group; and a list that maps each relevant {@code HederaFunctionality}
	 * to the number of logical operations it requires from the throttle.
	 */
	public Pair<DeterministicThrottle, List<Pair<HederaFunctionality, Integer>>> asThrottleMapping(int n) {
		int numGroups = throttleGroups.size();
		if (numGroups == 0) {
			throw new IllegalStateException("Bucket " + name + " includes no throttle groups!");
		}

		int logicalMtps = requiredLogicalMilliTpsToAccommodateAllGroups();
		if (logicalMtps < 0) {
			throw new IllegalStateException("Bucket " + name + " overflows with given throttle groups!");
		}

		var throttle = DeterministicThrottle.withMtpsAndBurstPeriod(logicalMtps / n, burstPeriod);
		long totalCapacityUnits = throttle.usageSnapshot().capacity();

		Set<HederaFunctionality> seenSoFar = new HashSet<>();
		List<Pair<HederaFunctionality, Integer>> opsReqs = new ArrayList<>();
		for (var throttleGroup : throttleGroups) {
			int opsReq = logicalMtps / throttleGroup.getMilliOpsPerSec();
			long capacityReq = opsReq * BucketThrottle.capacityUnitsPerTxn();
			if (capacityReq > totalCapacityUnits) {
				throw new IllegalStateException(
						"Bucket " + name + " contains an unsatisfiable opsPerSec with " + n + " nodes!");
			}
			var functions = throttleGroup.getOperations();
			if (Collections.disjoint(seenSoFar, functions)) {
				for (var function : functions) {
					opsReqs.add(Pair.of(function, opsReq));
				}
				seenSoFar.addAll(functions);
			} else {
				throw new IllegalStateException("Bucket " + name + " repeats an operation!");
			}
		}

		return Pair.of(throttle, opsReqs);
	}

	private int requiredLogicalMilliTpsToAccommodateAllGroups() {
		long lcm = throttleGroups.get(0).getMilliOpsPerSec();
		for (int i = 1, n = throttleGroups.size(); i < n; i++) {
			lcm = lcm(lcm, throttleGroups.get(i).getMilliOpsPerSec());
		}
		return (int)lcm;
	}

	private long lcm(long a, long b) {
		return (a * b) / gcd(Math.min(a, b), Math.max(a, b));
	}

	private long gcd(long a, long b) {
		return (a == 0) ? b : gcd(b % a, a);
	}
}
