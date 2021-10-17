package com.hedera.services.bdd.suites.perf.contract;

import com.google.common.util.concurrent.AtomicDouble;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.ADD_NTH_FIB_ABI;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.FIBONACCI_PLUS_CONSTRUCTOR_ABI;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.FIBONACCI_PLUS_PATH;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getExecTime;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.perf.PerfUtilOps.mgmtOfIntProp;
import static com.hedera.services.bdd.suites.perf.PerfUtilOps.stdMgmtOf;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.lang.Math.ceil;
import static java.util.concurrent.TimeUnit.SECONDS;

public class FibonacciPlusLoadProvider extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(FibonacciPlusLoadProvider.class);

	private static final int CALL_TPS = 100;
	private static final int SMALLEST_NUM_SLOTS = 32;
	private static final int SLOTS_PER_CALL = 12;
	private static final int APPROX_NUM_CONTRACTS = 1000;
	private static final int FIBONACCI_NUM_TO_USE = 12;
	private static final long SECS_TO_RUN = 600;

	private static final String SUITE_PROPS_PREFIX = "fibplus_";

	private static final int POWER_LAW_BASE_RECIPROCAL = 4;
	private static final double POWER_LAW_SCALE = 2;
	private static final double MIN_CALL_PROB = 0.90;

	private final AtomicLong duration = new AtomicLong(SECS_TO_RUN);
	private final AtomicDouble minCallProb = new AtomicDouble(MIN_CALL_PROB);
	private final AtomicDouble powerLawScale = new AtomicDouble(POWER_LAW_SCALE);
	private final AtomicInteger powerLawBaseReciprocal = new AtomicInteger(POWER_LAW_BASE_RECIPROCAL);
	private final AtomicInteger maxOpsPerSec = new AtomicInteger(CALL_TPS);
	private final AtomicInteger smallestNumSlots = new AtomicInteger(SMALLEST_NUM_SLOTS);
	private final AtomicInteger slotsPerCall = new AtomicInteger(SLOTS_PER_CALL);
	private final AtomicInteger numContracts = new AtomicInteger(APPROX_NUM_CONTRACTS);
	private final AtomicInteger fibN = new AtomicInteger(FIBONACCI_NUM_TO_USE);

	private final AtomicLong gasUsed = new AtomicLong(0);
	private final AtomicInteger submittedOps = new AtomicInteger(0);
	private final AtomicInteger completedOps = new AtomicInteger(0);

	private final AtomicReference<Instant> effStart = new AtomicReference<>();
	private final AtomicReference<Instant> effEnd = new AtomicReference<>();
	private final AtomicReference<TimeUnit> unit = new AtomicReference<>(SECONDS);

	private final Map<String, Integer> contractSlots = new HashMap<>();
	private final Set<String> createdSoFar = ConcurrentHashMap.newKeySet();

	public static void main(String... args) {
		final var suite = new FibonacciPlusLoadProvider();
		suite.runSuiteSync();
	}

	public void logResults() {
		final var start = effStart.get();
		final var end = effEnd.get();
		if (start == null || end == null) {
			return;
		}
		final var secs = Duration.between(start, end).toSeconds();
		final var gasPerSec = gasUsed.get() / secs;
		final var summary = "Consumed "
				+ gasUsed.get() + " gas (~" + gasPerSec + " gas/sec) in "
				+ completedOps.get() + " completed ops at attempted "
				+ maxOpsPerSec.get() + " ops/sec";
		log.info(summary);
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[] {
//						justDoOne(),
						addFibNums(),
				}
		);
	}

	private HapiApiSpec addFibNums() {
		return defaultHapiSpec("AddFibNums")
				.given(
						stdMgmtOf(duration, unit, maxOpsPerSec, SUITE_PROPS_PREFIX),
						mgmtOfIntProp(smallestNumSlots, SUITE_PROPS_PREFIX + "smallestNumSlots"),
						mgmtOfIntProp(slotsPerCall, SUITE_PROPS_PREFIX + "slotsPerCall"),
						mgmtOfIntProp(numContracts, SUITE_PROPS_PREFIX + "numContracts"),
						withOpContext((spec, opLog) -> {
							opLog.info("Resolved configuration:\n  " +
											SUITE_PROPS_PREFIX + "duration={}\n  " +
											SUITE_PROPS_PREFIX + "maxOpsPerSec={}\n  " +
											SUITE_PROPS_PREFIX + "numContracts={}\n  " +
											SUITE_PROPS_PREFIX + "smallestNumSlots={}\n  " +
											SUITE_PROPS_PREFIX + "powerLawScale={}\n  " +
											SUITE_PROPS_PREFIX + "powerLawBaseReciprocal={}\n  " +
											SUITE_PROPS_PREFIX + "minCallProb={}\n  " +
											SUITE_PROPS_PREFIX + "fibN={}\n  " +
											SUITE_PROPS_PREFIX + "slotsPerCall={}",
									duration.get(), maxOpsPerSec.get(), numContracts.get(),
									smallestNumSlots.get(), powerLawScale.get(),
									powerLawBaseReciprocal.get(), minCallProb.get(),
									fibN.get(), slotsPerCall.get());
						})
				).when().then(
						runWithProvider(contractOpsFactory())
								.lasting(duration::get, unit::get)
								.maxOpsPerSec(maxOpsPerSec::get),
						sleepFor(30_000L),
						withOpContext((spec, opLog) -> logResults())
				);
	}

	private Function<HapiApiSpec, OpProvider> contractOpsFactory() {
		final String civilian = "civilian";
		final String bytecode = "bytecode";
		final SplittableRandom random = new SplittableRandom(1_234_567L);
		final IntFunction<String> contractNameFn = i -> "contract" + i;

		final int r = powerLawBaseReciprocal.get();
		final DoubleUnaryOperator logBaseReciprocal = x -> Math.log(x) / Math.log(r);
		final int numDiscreteSizes = (int) ceil(logBaseReciprocal.applyAsDouble(numContracts.get() * (r - 1)));

		double scale = powerLawScale.get();
		int numSlots = (int) Math.pow(scale, numDiscreteSizes - 1) * smallestNumSlots.get();
		int numContractsWithThisManySlots = 1;
		int nextContractNum = 0;
		for (int i = 0; i < numDiscreteSizes; i++) {
			log.info("Will use {} contracts with {} slots", numContractsWithThisManySlots, numSlots);
			for (int j = 0; j < numContractsWithThisManySlots; j++) {
				contractSlots.put(contractNameFn.apply(nextContractNum++), numSlots);
			}
			numSlots /= scale;
			numContractsWithThisManySlots *= r;
		}
		log.info("Will use {} contracts in total", nextContractNum);
		numContracts.set(nextContractNum);

		Supplier<String> randomCallChoice = () -> {
			final var iter = createdSoFar.iterator();
			final var n = createdSoFar.size();
			if (n == 1) {
				return iter.next();
			}
			for (int i = 0; i < random.nextInt(n - 1); i++, iter.next()) {
				/* No-op */
			}
			return iter.next();
		};

		final var that = this;

		return spec -> new OpProvider() {
			@Override
			public List<HapiSpecOperation> suggestedInitializers() {
				final List<HapiSpecOperation> inits = new ArrayList<>();
				inits.add(fileCreate(bytecode)
						.path(FIBONACCI_PLUS_PATH)
						.noLogging()
						.payingWith(GENESIS));
				inits.add(cryptoCreate(civilian).balance(100 * ONE_MILLION_HBARS).payingWith(GENESIS));
				return inits;
			}

			@Override
			public Optional<HapiSpecOperation> get() {
				final var aCallNum = submittedOps.incrementAndGet();
				if (aCallNum == 1) {
					effStart.set(Instant.now());
				}

				final var choice = (createdSoFar.isEmpty() || random.nextDouble() > MIN_CALL_PROB)
						? contractNameFn.apply(random.nextInt(numContracts.get()))
						: randomCallChoice.get();
				final HapiSpecOperation op;
				if (createdSoFar.contains(choice)) {
					final var n = slotsPerCall.get();
					final int[] targets = new int[n];
					final var m = contractSlots.get(choice);
					for (int i = 0; i < n; i++) {
						targets[i] = random.nextInt(m);
					}
					final var targetsDesc = Arrays.toString(targets);
					log.info("Calling {} with targets {} and fibN {}",
							choice, targetsDesc, fibN.get());

					op = contractCall(choice, ADD_NTH_FIB_ABI, targets, fibN.get())
							.noLogging()
							.payingWith(civilian)
							.gas(300_000L)
							.exposingGasTo((code, gas) -> {
								log.info("(Tried to) call {} (targets = {}, fibN = {}) with {} gas --> {}",
										choice, targetsDesc, fibN.get(), gas, code);
								that.observeExposedGas(gas);
							})
							.hasKnownStatusFrom(SUCCESS, CONTRACT_REVERT_EXECUTED, INSUFFICIENT_GAS)
							.deferStatusResolution();
				} else {
					final var numSlots = contractSlots.get(choice);
					op = contractCreate(choice, FIBONACCI_PLUS_CONSTRUCTOR_ABI, numSlots)
							.bytecode(bytecode)
							.payingWith(civilian)
							.balance(0L)
							.gas(300_000L)
							.exposingGasTo((code, gas) -> {
								if (code == SUCCESS) {
									createdSoFar.add(choice);
								}
								log.info("(Tried to) create {} ({} slots) with {} gas --> {}",
										choice, numSlots, gas, code);
								that.observeExposedGas(gas);
							}).noLogging()
							.hasKnownStatusFrom(SUCCESS, INSUFFICIENT_GAS)
							.deferStatusResolution();
				}

				return Optional.of(op);
			}
		};
	}

	private void observeExposedGas(final long gas) {
		final var bCallNum = completedOps.incrementAndGet();
		if (bCallNum == submittedOps.get()) {
			effEnd.set(Instant.now());
		}
		gasUsed.addAndGet(gas);
	}

	private HapiApiSpec justDoOne() {
		final var civilian = "civilian";
		final var bytecode = "bytecode";
		final var contract = "fibPlus";
		final int[] firstTargets = { 19, 24 };
		final int[] secondTargets = { 30, 31 };
		final var firstCallTxn = "firstCall";
		final var secondCallTxn = "secondCall";
		final var createTxn = "creation";

		final AtomicReference<Instant> callStart = new AtomicReference<>();
		final AtomicReference<Instant> createStart = new AtomicReference<>();

		return defaultHapiSpec("JustDoOne")
				.given(
						fileCreate(bytecode)
								.path(FIBONACCI_PLUS_PATH)
								.noLogging()
								.payingWith(GENESIS),
						cryptoCreate(civilian).balance(100 * ONE_MILLION_HBARS).payingWith(GENESIS)
				).when(
						contractCreate(contract, FIBONACCI_PLUS_CONSTRUCTOR_ABI, 32)
								.bytecode(bytecode)
								.payingWith(civilian)
								.balance(0L)
								.gas(300_000L)
								.exposingGasTo((code, gas) -> {
									log.info("Got {} for creation using {} gas", code, gas);
									this.observeExposedGas(gas);
								}).via(createTxn),
						getExecTime(createTxn).logged()
				).then(
						sourcing(() -> {
							callStart.set(Instant.now());
							return noOp();
						}),
						contractCall(contract, ADD_NTH_FIB_ABI, firstTargets, FIBONACCI_NUM_TO_USE)
								.payingWith(civilian)
								.gas(300_000L)
								.exposingGasTo((code, gas) -> {
									final var done = Instant.now();
									log.info("Called FIRST in {}ms using {} gas --> {}",
											Duration.between(callStart.get(), done).toMillis(),
											gas, code);
									callStart.set(done);
								}).via(firstCallTxn),
						getExecTime(firstCallTxn).logged(),
						contractCall(contract, ADD_NTH_FIB_ABI, secondTargets, FIBONACCI_NUM_TO_USE)
								.payingWith(civilian)
								.gas(300_000L)
								.exposingGasTo((code, gas) -> {
									final var done = Instant.now();
									log.info("Called SECOND in {}ms using {} gas --> {}",
											Duration.between(callStart.get(), done).toMillis(),
											gas, code);
								}).via(secondCallTxn),
						getExecTime(secondCallTxn).logged()
				);
	}


	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
