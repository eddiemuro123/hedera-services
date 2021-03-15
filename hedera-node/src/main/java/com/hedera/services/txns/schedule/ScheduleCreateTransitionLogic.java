package com.hedera.services.txns.schedule;

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

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.context.TransactionContext;
import com.hedera.services.keys.InHandleActivationHelper;
import com.hedera.services.state.expiry.ExpiringEntity;
import com.hedera.services.state.merkle.MerkleSchedule;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.store.schedule.ScheduleStore;
import com.hedera.services.txns.TransitionLogic;
import com.hedera.services.txns.validation.OptionValidator;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleCreateTransactionBody;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.EnumSet;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.hedera.services.state.submerkle.RichInstant.fromJava;
import static com.hedera.services.txns.validation.ScheduleChecks.checkAdminKey;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.IDENTICAL_SCHEDULE_ALREADY_CREATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_NEW_VALID_SIGNATURES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

public class ScheduleCreateTransitionLogic extends ScheduleReadyForExecution implements TransitionLogic {
	private static final Logger log = LogManager.getLogger(ScheduleCreateTransitionLogic.class);

	private static final EnumSet<ResponseCodeEnum> ACCEPTABLE_SIGNING_OUTCOMES = EnumSet.of(OK, NO_NEW_VALID_SIGNATURES);
	static final long UNKNOWN_EXPIRY = 0L;

	private final OptionValidator validator;
	private final InHandleActivationHelper activationHelper;
	private final Function<TransactionBody, ResponseCodeEnum> SYNTAX_CHECK = this::validate;

	ExecutionProcessor executor = this::processExecution;
	MerkleScheduleFactory scheduleFactory = MerkleSchedule::from;
	SigMapScheduleClassifier classifier = new SigMapScheduleClassifier();
	SignatoryUtils.ScheduledSigningsWitness signingsWitness = SignatoryUtils::witnessScoped;

	public ScheduleCreateTransitionLogic(
			ScheduleStore store,
			TransactionContext txnCtx,
			InHandleActivationHelper activationHelper,
			OptionValidator validator
	) {
		super(store, txnCtx);
		this.activationHelper = activationHelper;
		this.validator = validator;
	}

	@Override
	public void doStateTransition() {
		try {
			var accessor = txnCtx.accessor();
			transitionFor(accessor.getTxnBytes(), accessor.getSigMap());
		} catch (Exception e) {
			log.warn("Unhandled error while processing :: {}!", txnCtx.accessor().getSignedTxn4Log(), e);
			abortWith(FAIL_INVALID);
		}
	}

	private void transitionFor(byte[] bodyBytes, SignatureMap sigMap) throws InvalidProtocolBufferException {
		var query = store.lookupSchedule(bodyBytes);
		if (query.getLeft().isPresent()) {
			completeContextWith(
					query.getLeft().get(),
					query.getRight(),
					IDENTICAL_SCHEDULE_ALREADY_CREATED);
			return;
		}

		var schedule = query.getRight();
		var result = store.createProvisionally(schedule, fromJava(txnCtx.consensusTime()));
		if (result.getCreated().isEmpty()) {
			abortWith(result.getStatus());
			return;
		}

		var scheduleId = result.getCreated().get();
		var validScheduleKeys = classifier.validScheduleKeys(
				txnCtx.activePayerKey(),
				sigMap,
				activationHelper.currentSigsFn(),
				activationHelper::visitScheduledCryptoSigs);
		var signingOutcome = signingsWitness.observeInScope(scheduleId, store, validScheduleKeys, activationHelper);
		if (!ACCEPTABLE_SIGNING_OUTCOMES.contains(signingOutcome.getLeft())) {
			abortWith(signingOutcome.getLeft());
			return;
		}

		if (store.isCreationPending()) {
			store.commitCreation();
			var expiringEntity = new ExpiringEntity(
					EntityId.ofNullableScheduleId(scheduleId),
					store::expire,
					schedule.expiry());
			txnCtx.addExpiringEntities(Collections.singletonList(expiringEntity));
		}

		var finalOutcome = OK;
		if (signingOutcome.getRight()) {
			finalOutcome = executor.doProcess(scheduleId);
		}
		completeContextWith(scheduleId, schedule, finalOutcome == OK ? SUCCESS : finalOutcome);
	}

	private void completeContextWith(ScheduleID scheduleID, MerkleSchedule schedule, ResponseCodeEnum finalOutcome) {
		txnCtx.setCreated(scheduleID);
		txnCtx.setScheduledTxnId(schedule.scheduledTransactionId());
		txnCtx.setStatus(finalOutcome);
	}

	private void abortWith(ResponseCodeEnum cause) {
		if (store.isCreationPending()) {
			store.rollbackCreation();
		}
		txnCtx.setStatus(cause);
	}

	@Override
	public Predicate<TransactionBody> applicability() {
		return TransactionBody::hasReplScheduleCreate;
	}

	@Override
	public Function<TransactionBody, ResponseCodeEnum> syntaxCheck() {
		return SYNTAX_CHECK;
	}

	public ResponseCodeEnum validate(TransactionBody txnBody) {
		var validity = OK;
		var op = txnBody.getReplScheduleCreate();
		validity = checkAdminKey(op.hasAdminKey(), op.getAdminKey());
		if (validity != OK) {
			return validity;
		}

		if (!validator.isValidEntityMemo(op.getMemo())) {
			return MEMO_TOO_LONG;
		}
		return validity;
	}

	@FunctionalInterface
	interface MerkleScheduleFactory {
		MerkleSchedule createFrom(byte[] bodyBytes, long expiry);
	}
}
