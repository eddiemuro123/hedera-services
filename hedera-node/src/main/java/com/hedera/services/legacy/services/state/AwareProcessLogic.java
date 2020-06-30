package com.hedera.services.legacy.services.state;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
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
import com.google.protobuf.TextFormat;
import com.hedera.services.context.ServicesContext;

import static com.hedera.services.context.domain.trackers.IssEventStatus.ONGOING_ISS;
import static com.hedera.services.keys.HederaKeyActivation.payerSigIsActive;
import static com.hedera.services.keys.HederaKeyActivation.otherPartySigsAreActive;

import com.hedera.services.keys.RevocationServiceCharacteristics;
import com.hedera.services.legacy.config.PropertiesLoader;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.legacy.core.jproto.JKeyList;
import com.hedera.services.legacy.core.jproto.ExpirableTxnRecord;
import com.hedera.services.legacy.crypto.SignatureStatus;
import com.hedera.services.legacy.crypto.SignatureStatusCode;
import com.hedera.services.legacy.utils.TransactionValidationUtils;
import com.hedera.services.txns.ProcessLogic;
import com.hedera.services.txns.TransitionLogic;
import com.hedera.services.sigs.sourcing.DefaultSigBytesProvider;
import com.hedera.services.txns.diligence.DuplicateClassification;
import com.hedera.services.utils.MiscUtils;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionReceipt;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.fee.FeeObject;
import com.swirlds.common.Transaction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import static com.hedera.services.sigs.Rationalization.IN_HANDLE_SUMMARY_FACTORY;
import static com.hedera.services.txns.diligence.DuplicateClassification.DUPLICATE;
import static com.hedera.services.txns.diligence.DuplicateClassification.NODE_DUPLICATE;
import static com.hedera.services.utils.EntityIdUtils.readableId;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;
import static com.hedera.services.sigs.HederaToPlatformSigOps.rationalizeIn;
import static java.time.ZoneOffset.UTC;
import static java.time.temporal.ChronoUnit.SECONDS;

public class AwareProcessLogic implements ProcessLogic {
	private static final Logger log = LogManager.getLogger(AwareProcessLogic.class);
	private static final EnumSet<ResponseCodeEnum> SIG_RATIONALIZATION_ERRORS = EnumSet.of(
			INVALID_FILE_ID,
			INVALID_ACCOUNT_ID,
			INVALID_SIGNATURE,
			KEY_PREFIX_MISMATCH,
			INVALID_SIGNATURE_COUNT_MISMATCHING_KEY,
			MODIFYING_IMMUTABLE_CONTRACT,
			INVALID_CONTRACT_ID);

	private final ServicesContext ctx;

	public AwareProcessLogic(ServicesContext ctx) {
		this.ctx = ctx;
	}

	public static boolean inSameUtcDay(Instant now, Instant then) {
		return LocalDateTime.ofInstant(now, UTC).getDayOfYear() == LocalDateTime.ofInstant(then, UTC).getDayOfYear();
	}

	@Override
	public void incorporateConsensusTxn(Transaction platformTxn, Instant consensusTime, long submittingMember) {
		try {
			PlatformTxnAccessor accessor = new PlatformTxnAccessor(platformTxn);
			processInLedgerTxn(accessor, consensusTime, submittingMember);
		} catch (InvalidProtocolBufferException e) {
			log.warn("Consensus platform txn was not gRPC!", e);
		}
	}

	private void processInLedgerTxn(PlatformTxnAccessor accessor, Instant consensusTime, long submittingMember) {
		boolean wasCommitted = false;

		try {
			ctx.ledger().begin();
			ctx.txnCtx().resetFor(accessor, consensusTime, submittingMember);
			processTxnInCtx();
		} catch (Exception unhandled) {
			warnOf(unhandled, "txn processing");
			ctx.txnCtx().setStatus(FAIL_INVALID);
		} finally {
			try {
				ctx.ledger().commit();
				wasCommitted = true;
			} catch (Exception unrecoverable) {
				warnOf(unrecoverable, "txn commit");
				ctx.recordCache().setFailInvalid(accessor, consensusTime);
				ctx.ledger().rollback();
			} finally {
				if (wasCommitted) {
					try {
						addRecordToStream();
					} catch (Exception unknown) {
						warnOf(unknown, "record streaming");
					}
				}
			}
		}
	}

	private void processTxnInCtx() {
		doProcess(ctx.txnCtx().accessor(), ctx.txnCtx().consensusTime());
	}

	private void warnOf(Exception e, String context) {
		String tpl = "Possibly CATASTROPHIC failure in {} :: {} ==>> {} ==>>";
		try {
			log.warn(
					tpl,
					context,
					ctx.txnCtx().accessor().getSignedTxn4Log(),
					ctx.ledger().currentChangeSet(),
					e);
		} catch (Exception unexpected) {
			log.warn("Failure in {} ::", context, e);
			log.warn("Full details could not be logged!", unexpected);
		}
	}

	private void addRecordToStream() {
		TransactionRecord finalRecord = ExpirableTxnRecord.toGrpc(ctx.recordsHistorian().lastCreatedRecord().get());
		addForStreaming(ctx.txnCtx().accessor().getSignedTxn(), finalRecord, ctx.txnCtx().consensusTime());
	}

	private void doProcess(PlatformTxnAccessor accessor, Instant consensusTime) {
		/* Side-effects of advancing data-driven clock to consensus time. */
		updateMidnightRatesIfAppropriateAt(consensusTime);
		ctx.updateConsensusTimeOfLastHandledTxn(consensusTime);
		ctx.recordsHistorian().purgeExpiredRecords();
		ctx.duplicateClassifier().shiftDetectionWindow();

		if (ctx.issEventInfo().status() == ONGOING_ISS) {
			var resetPeriod = ctx.properties().getIntProperty("iss.reset.periodSecs");
			var resetTime = ctx.issEventInfo().consensusTimeOfRecentAlert().get().plus(resetPeriod, SECONDS);
			if (consensusTime.isAfter(resetTime)) {
				ctx.issEventInfo().relax();
			}
		}

		final SignatureStatus sigStatus = rationalizeWithPreConsensusSigs(accessor);
		if (hasActivePayerSig(accessor)) {
			ctx.txnCtx().payerSigIsKnownActive();
		}

		FeeObject fee = ctx.fees().computeFee(accessor, ctx.txnCtx().activePayerKey(), ctx.currentView());
		DuplicateClassification duplicity = ctx.duplicateClassifier().duplicityOfActiveTxn();
		if (nodeIgnoredDueDiligence(duplicity)) {
			ctx.txnChargingPolicy().applyForIgnoredDueDiligence(ctx.charging(), fee);
			return;
		}

		if (duplicity == DUPLICATE) {
			ctx.txnChargingPolicy().applyForDuplicate(ctx.charging(), fee);
			ctx.txnCtx().setStatus(DUPLICATE_TRANSACTION);
			return;
		}

		ResponseCodeEnum chargingStatus = ctx.txnChargingPolicy().apply(ctx.charging(), fee);
		if (chargingStatus != OK) {
			ctx.txnCtx().setStatus(chargingStatus);
			return;
		}

		if (SIG_RATIONALIZATION_ERRORS.contains(sigStatus.getResponseCode())) {
			ctx.txnCtx().setStatus(sigStatus.getResponseCode());
			return;
		}

		if (!hasActiveNonPayerEntitySigs(accessor)) {
			ctx.txnCtx().setStatus(INVALID_SIGNATURE);
			return;
		}

		ResponseCodeEnum metaValidity = assessPostConsensusValidity(accessor, consensusTime);
		if (metaValidity != OK) {
			ctx.txnCtx().setStatus(metaValidity);
			return;
		}

		Optional<TransitionLogic> transitionLogic = ctx.transitionLogic().lookupFor(accessor.getTxn());
		ResponseCodeEnum opValidity = transitionLogic.isPresent()
				? transitionLogic.get().syntaxCheck().apply(accessor.getTxn())
				: TransactionValidationUtils.validateTxSpecificBody(accessor.getTxn(), ctx.validator());

		if (opValidity != OK) {
			ctx.txnCtx().setStatus(opValidity);
			return;
		}

		if (transitionLogic.isPresent()) {
			transitionLogic.get().doStateTransition();
		} else {
			TransactionRecord record = processTransaction(accessor.getTxn(), consensusTime);
			if (record != null && record.isInitialized()) {
				mapLegacyRecordToTxnCtx(record);
			} else {
				log.warn("Legacy process returned null record for {}!", accessor.getTxn());
			}
		}

		ctx.stats().transactionHandled(MiscUtils.getTxnStat(accessor.getTxn()));
	}

	private ResponseCodeEnum assessPostConsensusValidity(PlatformTxnAccessor accessor, Instant consensusTime) {
		return TransactionValidationUtils.validateTxBodyPostConsensus(accessor.getTxn(), consensusTime, ctx.accounts());
	}

	private boolean hasActivePayerSig(PlatformTxnAccessor accessor) {
		try {
			return payerSigIsActive(accessor, ctx.keyOrder(), IN_HANDLE_SUMMARY_FACTORY);
		} catch (Exception edgeCase) {
			log.warn("Almost inconceivably, when testing payer sig activation:", edgeCase);
		}
		return false;
	}

	private boolean hasActiveNonPayerEntitySigs(PlatformTxnAccessor accessor) {
		if (isMeaningfulFileDelete(accessor.getTxn())) {
			var id = accessor.getTxn().getFileDelete().getFileID();
			JKey wacl = ctx.hfs().getattr(id).getWacl();
			return otherPartySigsAreActive(
					accessor,
					ctx.keyOrder(),
					IN_HANDLE_SUMMARY_FACTORY,
					RevocationServiceCharacteristics.forTopLevelFile((JKeyList)wacl));
		} else {
			return otherPartySigsAreActive(accessor, ctx.keyOrder(), IN_HANDLE_SUMMARY_FACTORY);
		}
	}

	private boolean isMeaningfulFileDelete(TransactionBody txn) {
		if (!txn.hasFileDelete() || !txn.getFileDelete().hasFileID()) {
			return false;
		} else {
			return ctx.hfs().exists(txn.getFileDelete().getFileID());
		}
	}

	private boolean nodeIgnoredDueDiligence(DuplicateClassification duplicity) {
		Instant consensusTime = ctx.txnCtx().consensusTime();
		PlatformTxnAccessor accessor = ctx.txnCtx().accessor();

		AccountID swirldsMemberAccount = ctx.txnCtx().submittingNodeAccount();
		AccountID designatedNodeAccount = accessor.getTxn().getNodeAccountID();
		if (!swirldsMemberAccount.equals(designatedNodeAccount)) {
			log.warn("Node {} (Swirlds #{}) submitted a txn designated for node {} :: {}",
					readableId(swirldsMemberAccount),
					ctx.txnCtx().submittingSwirldsMember(),
					readableId(designatedNodeAccount),
					accessor.getSignedTxn4Log());
			ctx.txnCtx().setStatus(INVALID_NODE_ACCOUNT);
			return true;
		}

		if (!ctx.txnCtx().isPayerSigKnownActive()) {
			ctx.txnCtx().setStatus(INVALID_PAYER_SIGNATURE);
			return true;
		}

		if (duplicity == NODE_DUPLICATE) {
			ctx.txnCtx().setStatus(DUPLICATE_TRANSACTION);
			return true;
		}

		long txnDuration = accessor.getTxn().getTransactionValidDuration().getSeconds();
		if (!ctx.validator().isValidTxnDuration(txnDuration)) {
			ctx.txnCtx().setStatus(INVALID_TRANSACTION_DURATION);
			return true;
		}

		ResponseCodeEnum chronologyStatus = ctx.validator().chronologyStatus(accessor, consensusTime);
		if (chronologyStatus != OK) {
			ctx.txnCtx().setStatus(chronologyStatus);
			return true;
		}

		return false;
	}

	private SignatureStatus rationalizeWithPreConsensusSigs(PlatformTxnAccessor accessor) {
		SignatureStatus sigStatus =
				rationalizeIn(accessor, ctx.syncVerifier(), ctx.keyOrder(), DefaultSigBytesProvider.DEFAULT_SIG_BYTES);
		if (!sigStatus.isError()) {
			ctx.stats().signatureVerified(sigStatus.getStatusCode() == SignatureStatusCode.SUCCESS_VERIFY_ASYNC);
		}
		return sigStatus;
	}

	private void updateMidnightRatesIfAppropriateAt(Instant dataDrivenNow) {
		if (shouldUpdateMidnightRatesAt(dataDrivenNow)) {
			ctx.midnightRates().replaceWith(ctx.exchange().activeRates());
		}
	}
	private boolean shouldUpdateMidnightRatesAt(Instant dataDrivenNow) {
		return ctx.consensusTimeOfLastHandledTxn() != null &&
				!inSameUtcDay(ctx.consensusTimeOfLastHandledTxn(), dataDrivenNow);
	}

	private void mapLegacyRecordToTxnCtx(TransactionRecord legacyRecord) {
		ctx.txnCtx().setStatus(legacyRecord.getReceipt().getStatus());

		if (legacyRecord.hasContractCallResult()) {
			ctx.txnCtx().setCallResult(legacyRecord.getContractCallResult());
		} else if (legacyRecord.hasContractCreateResult()) {
			ctx.txnCtx().setCreateResult(legacyRecord.getContractCreateResult());
			if (ctx.txnCtx().status() == SUCCESS) {
				ctx.txnCtx().setCreated(legacyRecord.getReceipt().getContractID());
			}
		}
	}

	private void addForStreaming(
			com.hederahashgraph.api.proto.java.Transaction grpcTransaction,
			TransactionRecord transactionRecord,
			Instant consensusTimeStamp
	) {
		if (PropertiesLoader.isEnableRecordStreaming()) {
			ctx.recordStream().addRecord(grpcTransaction, transactionRecord, consensusTimeStamp);
		}
	}

	private TransactionRecord processTransaction(TransactionBody txn, Instant consensusTime) {
		TransactionRecord record = null;
		if (txn.hasContractCreateInstance()) {
			FileID fid = txn.getContractCreateInstance().getFileID();
			try {
				if (!ctx.hfs().exists(fid)) {
					record = ctx.contracts().getFailureTransactionRecord(txn, consensusTime, INVALID_FILE_ID);
				} else {
					byte[] contractByteCode = ctx.hfs().cat(fid);
					if (contractByteCode.length > 0) {
						record = ctx.contracts().createContract(txn, consensusTime, contractByteCode, ctx.seqNo());
					} else {
						record = ctx.contracts().getFailureTransactionRecord(txn, consensusTime, CONTRACT_FILE_EMPTY);
					}
				}
			} catch (Exception e) {
				log.error("Error during create contract", e);
			}

		} else if (txn.hasContractCall()) {
			try {
				record = ctx.contracts().contractCall(txn, consensusTime, ctx.seqNo());
			} catch (Exception e) {
				log.error("Error during create contract", e);
			}
		} else if (txn.hasContractUpdateInstance()) {
			try {
				record = ctx.contracts().updateContract(txn, consensusTime);
			} catch (Exception e) {
				log.error("Error during update contract", e);
			}
		} else if (txn.hasContractDeleteInstance()) {
			try {
				record = ctx.contracts().deleteContract(txn, consensusTime);
			} catch (Exception e) {
				log.error("Error during delete contract", e);
			}
		} else if (txn.hasSystemDelete() && txn.getSystemDelete().hasContractID()) {
				record = ctx.contracts().systemDelete(txn, consensusTime);
		} else if (txn.hasSystemUndelete() && txn.getSystemUndelete().hasContractID()) {
				record = ctx.contracts().systemUndelete(txn, consensusTime);
		} else if (txn.hasFreeze()) {
			record = ctx.freeze().freeze(txn, consensusTime);
		} else {
			log.error("API is not implemented");
			record = TransactionRecord.getDefaultInstance();
		}
		if (record.hasReceipt()) {
			if (!record.getReceipt().hasExchangeRate()) {
				if(log.isDebugEnabled()) {
					log.debug("Receipt {} missing rates info!", TextFormat.shortDebugString(record));
				}
				TransactionReceipt.Builder receiptBuilder = record.getReceipt().toBuilder();
				receiptBuilder.setExchangeRate(ctx.exchange().activeRates());
				record = record.toBuilder().setReceipt(receiptBuilder).build();
			}
		}
		return record;
	}
}
