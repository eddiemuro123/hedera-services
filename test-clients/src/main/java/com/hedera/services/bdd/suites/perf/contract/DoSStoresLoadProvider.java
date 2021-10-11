package com.hedera.services.bdd.suites.perf.contract;

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.meta.ContractResources;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.DO_SSTORES_CONSTRUCTOR_ABI;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.HIT_SOME_TARGETS_ABI;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.perf.PerfUtilOps.mgmtOfIntProp;
import static com.hedera.services.bdd.suites.perf.PerfUtilOps.stdMgmtOf;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static java.util.concurrent.TimeUnit.SECONDS;

public class DoSStoresLoadProvider extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(DoSStoresLoadProvider.class);

	private static final long POST_CREATE_SLEEP_MS = 10_000L;

	private static final int CALL_TPS = 20;
	private static final int NUM_TARGETS = 2;
	private static final int STATE_ARRAY_LEN = 10;
	private static final int NUM_TARGET_CONTRACTS = 10;
	private static final long SECS_TO_RUN = 60;
	private static final String SUITE_PROPS_PREFIX = "dosstores_";

	private final AtomicLong duration = new AtomicLong(SECS_TO_RUN);
	private final AtomicInteger maxOpsPerSec = new AtomicInteger(CALL_TPS);
	private final AtomicReference<TimeUnit> unit = new AtomicReference<>(SECONDS);
	private final AtomicInteger stateArrayLen = new AtomicInteger(STATE_ARRAY_LEN);
	private final AtomicInteger numTargets = new AtomicInteger(NUM_TARGETS);
	private final AtomicInteger numTargetContracts = new AtomicInteger(NUM_TARGET_CONTRACTS);

	private final AtomicLong gasUsed = new AtomicLong(0);
	private final AtomicInteger submittedCalls = new AtomicInteger(0);
	private final AtomicInteger completedCalls = new AtomicInteger(0);
	private final AtomicReference<Instant> effStart = new AtomicReference<>();
	private final AtomicReference<Instant> effEnd = new AtomicReference<>();

	public static void main(String... args) {
		final var suite = new DoSStoresLoadProvider();
		suite.runSuiteSync();
		suite.logResults();
	}

	public void logResults() {
		final var secs = Duration.between(effStart.get(), effEnd.get()).toSeconds();
		final var gasPerSec = gasUsed.get() / secs;
		final var summary = "Consumed "
				+ gasUsed.get() + " gas (~" + gasPerSec + " gas/sec) in "
				+ completedCalls.get() + " completed calls at attempted "
				+ maxOpsPerSec.get() + " calls/sec, each setting " + numTargets.get()
				+ "/" + stateArrayLen.get() + " storage array targets";
		log.info(summary);
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[] {
						doSStores(),
				}
		);
	}

	private HapiApiSpec doSStores() {
		return defaultHapiSpec("doSStores")
				.given(
						stdMgmtOf(duration, unit, maxOpsPerSec, SUITE_PROPS_PREFIX),
						mgmtOfIntProp(numTargets, SUITE_PROPS_PREFIX + "numTargets"),
						mgmtOfIntProp(stateArrayLen, SUITE_PROPS_PREFIX + "stateArrayLen"),
						mgmtOfIntProp(numTargetContracts, SUITE_PROPS_PREFIX + "numTargetContracts"),
						withOpContext((spec, opLog) -> {
							opLog.info("Resolved configuration:\n  " +
											SUITE_PROPS_PREFIX + "duration={}\n  " +
											SUITE_PROPS_PREFIX + "maxOpsPerSec={}\n  " +
											SUITE_PROPS_PREFIX + "numTargetContracts={}\n  " +
											SUITE_PROPS_PREFIX + "stateArrayLen={}\n  " +
											SUITE_PROPS_PREFIX + "numTargets={}",
									duration.get(), maxOpsPerSec.get(), numTargetContracts.get(),
									stateArrayLen.get(), numTargets.get());
						})
				).when( ).then(
						runWithProvider(callsFactory())
								.lasting(duration::get, unit::get)
								.maxOpsPerSec(maxOpsPerSec::get)
				);
	}

	private Function<HapiApiSpec, OpProvider> callsFactory() {
		final String civilian = "civilian";
		final String bytecode = "bytecode";
		final SplittableRandom r = new SplittableRandom();
		final int len = stateArrayLen.get();
		final IntFunction<String> targetName = i -> "target" + (i % numTargetContracts.get());

		return spec -> new OpProvider() {
			@Override
			public List<HapiSpecOperation> suggestedInitializers() {
				final List<HapiSpecOperation> inits = new ArrayList<>();
				inits.add(cryptoCreate(civilian).balance(100 * ONE_MILLION_HBARS).payingWith(GENESIS));
				inits.add(fileCreate(bytecode)
						.path(ContractResources.DO_SSTORES_PATH)
						.noLogging()
						.payingWith(GENESIS));
				inits.add(
						inParallel(IntStream.range(0, numTargetContracts.get())
								.mapToObj(i -> contractCreate(targetName.apply(i), DO_SSTORES_CONSTRUCTOR_ABI, len)
										.bytecode(bytecode)
										.payingWith(GENESIS)
										.balance(0L)
										.noLogging()
										.hasRetryPrecheckFrom(DUPLICATE_TRANSACTION)
										.deferStatusResolution())
								.toArray(HapiSpecOperation[]::new)));
				inits.add(sleepFor(POST_CREATE_SLEEP_MS));
				return inits;
			}

			@Override
			public Optional<HapiSpecOperation> get() {
				final var aCallNum = submittedCalls.incrementAndGet();
				if (aCallNum == 1) {
					effStart.set(Instant.now());
				}
				final var choice = targetName.apply(r.nextInt(numTargetContracts.get()));

				final var n = numTargets.get();
				final int[] targets = new int[n];
				final var m = stateArrayLen.get();
				for (int i = 0; i < n; i++) {
					targets[i] = r.nextInt(m);
				}

				final var v = r.nextInt(1_234);
				final var op = contractCall(choice, HIT_SOME_TARGETS_ABI, targets, v)
						.noLogging()
						.payingWith(civilian)
						.exposingGasTo(gas -> {
							final var bCallNum = completedCalls.incrementAndGet();
							if (bCallNum == submittedCalls.get()) {
								effEnd.set(Instant.now());
							}
							gasUsed.addAndGet(gas);
						}).deferStatusResolution();
				return Optional.of(op);
			}
		};
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
