package com.hedera.services.txns.contract;

import com.hedera.services.context.TransactionContext;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.txns.TransitionLogic;
import com.hedera.services.txns.crypto.CryptoCreateTransitionLogic;
import com.hedera.services.txns.validation.OptionValidator;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.swirlds.fcmap.FCMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

public class ContractUpdateTransitionLogic implements TransitionLogic {
	private static final Logger log = LogManager.getLogger(ContractUpdateTransitionLogic.class);

	private final LegacyUpdater delegate;
	private final OptionValidator validator;
	private final TransactionContext txnCtx;
	private final Supplier<FCMap<MerkleEntityId, MerkleAccount>> contracts;

	private final Function<TransactionBody, ResponseCodeEnum> SYNTAX_CHECK = this::validate;

	public ContractUpdateTransitionLogic(
			LegacyUpdater delegate,
			OptionValidator validator,
			TransactionContext txnCtx,
			Supplier<FCMap<MerkleEntityId, MerkleAccount>> contracts
	) {
		this.delegate = delegate;
		this.validator = validator;
		this.txnCtx = txnCtx;
		this.contracts = contracts;
	}

	@FunctionalInterface
	public interface LegacyUpdater {
		TransactionRecord perform(TransactionBody txn, Instant consensusTime);
	}

	@Override
	public void doStateTransition() {
		try {
			var contractUpdateTxn = txnCtx.accessor().getTxn();
			var op = contractUpdateTxn.getContractUpdateInstance();

			if (!validator.isValidEntityMemo(op.getMemo())) {
				txnCtx.setStatus(MEMO_TOO_LONG);
				return;
			}

			var legacyRecord = delegate.perform(contractUpdateTxn, txnCtx.consensusTime());

			txnCtx.setStatus(legacyRecord.getReceipt().getStatus());
		} catch (Exception e) {
			log.warn("Avoidable exception!", e);
			txnCtx.setStatus(FAIL_INVALID);
		}
	}

	@Override
	public Predicate<TransactionBody> applicability() {
		return TransactionBody::hasContractUpdateInstance;
	}

	@Override
	public Function<TransactionBody, ResponseCodeEnum> syntaxCheck() {
		return SYNTAX_CHECK;
	}

	public ResponseCodeEnum validate(TransactionBody contractUpdateTxn) {
		var op = contractUpdateTxn.getContractUpdateInstance();
		var status = validator.queryableContractStatus(op.getContractID(), contracts.get());
		if (status != OK) {
			return status;
		}
		return (!op.hasAutoRenewPeriod() || validator.isValidAutoRenewPeriod(op.getAutoRenewPeriod()))
				? OK
				: AUTORENEW_DURATION_NOT_IN_RANGE;
	}
}
