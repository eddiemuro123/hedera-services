package com.hedera.services.bdd.suites.contract;

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.List;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

public class SmartContractFailFirstSpec extends HapiApiSuite  {
	private static final Logger log = LogManager.getLogger(SmartContractFailFirstSpec.class);

	final String PATH_TO_SIMPLE_STORAGE_BYTECODE = "src/main/resource/simpleStorage.bin";

	private static final String SC_GET_ABI = "{\"constant\":true,\"inputs\":[],\"name\":\"get\",\"outputs\":[{\"name\":\"\",\"type\":\"uint256\"}],\"payable\":false,\"stateMutability\":\"view\",\"type\":\"function\"}";
	private static final String SC_SET_ABI = "{\"constant\":false,\"inputs\":[{\"name\":\"x\",\"type\":\"uint256\"}],\"name\":\"set\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"}";


	public static void main(String... args) {
		new org.ethereum.crypto.HashUtil();

		new SmartContractFailFirstSpec().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[] {
				smartContractFailFirst(),
		});
	}

	HapiApiSpec smartContractFailFirst() {

		return defaultHapiSpec("smartContractFailFirst")
				.given(
						cryptoCreate("payer").balance( 10_000_000_000_000L).logged(),
						fileCreate("bytecode")
								.path(PATH_TO_SIMPLE_STORAGE_BYTECODE)

				).when(
						withOpContext((spec, ignore) -> {
							var subop1 = balanceSnapshot("balanceBefore0", "payer");

							var subop2 =
									contractCreate("failInsufficientGas")
											.balance(0)
											.payingWith("payer")
											.gas(1)
											.bytecode("bytecode")
											.hasKnownStatus(INSUFFICIENT_GAS)
											.via("failInsufficientGas");

							var subop3 = getTxnRecord("failInsufficientGas");
							CustomSpecAssert.allRunFor(spec, subop1, subop2, subop3);
							long delta = subop3.getResponseRecord().getTransactionFee();

							var subop4 = getAccountBalance("payer").hasTinyBars(changeFromSnapshot("balanceBefore0", -delta));
							CustomSpecAssert.allRunFor(spec,  subop4);

						}),



						withOpContext((spec, ignore) -> {
							var subop1 = balanceSnapshot("balanceBefore1", "payer");

							var subop2 = contractCreate("failInvalidInitialBalance")
									.balance(100_000_000_000L)
									.payingWith("payer")
									.gas(250_000L)
									.bytecode("bytecode")
									.via("failInvalidInitialBalance")
									.hasKnownStatus(CONTRACT_REVERT_EXECUTED);

							var subop3 = getTxnRecord("failInvalidInitialBalance");
							CustomSpecAssert.allRunFor(spec, subop1, subop2, subop3);
							long delta = subop3.getResponseRecord().getTransactionFee();

							var subop4 = getAccountBalance("payer").hasTinyBars(changeFromSnapshot("balanceBefore1", -delta));
							CustomSpecAssert.allRunFor(spec,  subop4);

						}),


						withOpContext((spec, ignore) -> {
							var subop1 = balanceSnapshot("balanceBefore2", "payer");

							var subop2 = contractCreate("successWithZeroInitialBalance")
									.balance(0L)
									.payingWith("payer")
									.gas(250_000L)
									.bytecode("bytecode")
									.hasKnownStatus(SUCCESS)
									.via("successWithZeroInitialBalance");

							var subop3 = getTxnRecord("successWithZeroInitialBalance");
							CustomSpecAssert.allRunFor(spec, subop1, subop2, subop3);
							long delta = subop3.getResponseRecord().getTransactionFee();

							var subop4 = getAccountBalance("payer").hasTinyBars(changeFromSnapshot("balanceBefore2", -delta));
							CustomSpecAssert.allRunFor(spec, subop4);

						}),

						withOpContext((spec, ignore) -> {
							var subop1 = balanceSnapshot("balanceBefore3", "payer");

							var subop2 = contractCall("successWithZeroInitialBalance", SC_SET_ABI, 999_999L )
									.payingWith("payer")
									.gas(300_000L)
									.hasKnownStatus(SUCCESS)
									.via("setValue");

							var subop3 = getTxnRecord("setValue");
							CustomSpecAssert.allRunFor(spec, subop1, subop2, subop3);
							long delta = subop3.getResponseRecord().getTransactionFee();

							var subop4 = getAccountBalance("payer").hasTinyBars(changeFromSnapshot("balanceBefore3", -delta));
							CustomSpecAssert.allRunFor(spec, subop4);

						}),


						withOpContext((spec, ignore) -> {
							var subop1 = balanceSnapshot("balanceBefore4", "payer");

							var subop2 = contractCall("successWithZeroInitialBalance", SC_GET_ABI)
									.payingWith("payer")
									.gas(300_000L)
									.hasKnownStatus(SUCCESS)
									.via("getValue");

							var subop3 = getTxnRecord("getValue");
							CustomSpecAssert.allRunFor(spec, subop1, subop2, subop3);
							long delta = subop3.getResponseRecord().getTransactionFee();

							var subop4 = getAccountBalance("payer").hasTinyBars(changeFromSnapshot("balanceBefore4", -delta));
							CustomSpecAssert.allRunFor(spec, subop4);

						})
						).then(
						getTxnRecord("failInsufficientGas"),
						getTxnRecord("successWithZeroInitialBalance"),
						getTxnRecord("failInvalidInitialBalance")
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
