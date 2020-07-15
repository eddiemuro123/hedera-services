package com.hedera.services.context;

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

import com.hedera.services.ServicesState;
import com.hedera.services.config.AccountNumbers;
import com.hedera.services.config.EntityNumbers;
import com.hedera.services.config.FileNumbers;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleNetworkContext;
import com.hedera.services.state.merkle.MerkleTopic;
import com.hedera.services.context.domain.trackers.ConsensusStatusCounts;
import com.hedera.services.context.domain.trackers.IssEventInfo;
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.context.properties.PropertySources;
import com.hedera.services.contracts.execution.SolidityLifecycle;
import com.hedera.services.contracts.execution.TxnAwareSoliditySigsVerifier;
import com.hedera.services.contracts.persistence.BlobStoragePersistence;
import com.hedera.services.fees.calculation.AwareFcfsUsagePrices;
import com.hedera.services.fees.calculation.UsageBasedFeeCalculator;
import com.hedera.services.fees.charging.ItemizableFeeCharging;
import com.hedera.services.fees.charging.TxnFeeChargingPolicy;
import com.hedera.services.files.TieredHederaFs;
import com.hedera.services.files.interceptors.FeeSchedulesManager;
import com.hedera.services.files.interceptors.TxnAwareAuthPolicy;
import com.hedera.services.files.interceptors.TxnAwareRatesManager;
import com.hedera.services.files.interceptors.ValidatingCallbackInterceptor;
import com.hedera.services.files.store.FcBlobsBytesStore;
import com.hedera.services.grpc.NettyGrpcServerManager;
import com.hedera.services.grpc.controllers.ConsensusController;
import com.hedera.services.grpc.controllers.CryptoController;
import com.hedera.services.grpc.controllers.FileController;
import com.hedera.services.grpc.controllers.NetworkController;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.ids.SeqNoEntityIdSource;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleBlobMeta;
import com.hedera.services.state.merkle.MerkleOptionalBlob;
import com.hedera.services.queries.answering.ServiceAnswerFlow;
import com.hedera.services.queries.consensus.HcsAnswers;
import com.hedera.services.queries.validation.QueryFeeCheck;
import com.hedera.services.state.initialization.HfsSystemFilesManager;
import com.hedera.services.state.submerkle.ExchangeRates;
import com.hedera.services.state.submerkle.RichInstant;
import com.hedera.services.state.submerkle.SequenceNumber;
import com.hedera.services.throttling.BucketThrottling;
import com.hedera.services.throttling.TransactionThrottling;
import com.hedera.services.txns.TransitionLogicLookup;
import com.hedera.services.txns.diligence.PerNodeDuplicateClassifier;
import com.hedera.services.txns.diligence.TxnAwareDuplicateClassifier;
import com.hedera.services.txns.submission.TxnHandlerSubmissionFlow;
import com.hedera.services.txns.submission.TxnResponseHelper;
import com.hedera.services.txns.validation.ContextOptionValidator;
import com.hedera.services.queries.answering.AnswerFunctions;
import com.hedera.services.queries.answering.QueryResponseHelper;
import com.hedera.services.queries.crypto.CryptoAnswers;
import com.hedera.services.queries.meta.MetaAnswers;
import com.hedera.services.records.FeePayingRecordsHistorian;
import com.hedera.services.records.RecordCache;
import com.hedera.services.sigs.order.HederaSigningOrder;
import com.hedera.services.sigs.verification.PrecheckVerifier;
import com.hedera.services.sigs.verification.SyncVerifier;
import com.hedera.services.state.migration.DefaultStateMigrations;
import com.hedera.services.utils.SleepingPause;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hedera.services.legacy.handler.FreezeHandler;
import com.hedera.services.legacy.handler.SmartContractRequestHandler;
import com.hedera.services.legacy.handler.TransactionHandler;
import com.hedera.services.contracts.sources.LedgerAccountsSource;
import com.hedera.services.contracts.sources.BlobStorageSource;
import com.hedera.services.legacy.service.FreezeServiceImpl;
import com.hedera.services.legacy.service.GlobalFlag;
import com.hedera.services.legacy.service.SmartContractServiceImpl;
import com.hedera.services.legacy.services.context.properties.DefaultPropertySanitizer;
import com.hedera.services.legacy.services.fees.DefaultFeeExemptions;
import com.hedera.services.legacy.services.fees.DefaultHbarCentExchange;
import com.hedera.services.legacy.services.state.AwareProcessLogic;
import com.hedera.services.legacy.services.state.export.DefaultBalancesExporter;
import com.hedera.services.legacy.services.state.initialization.DefaultSystemAccountsCreator;
import com.hedera.services.legacy.services.state.validation.DefaultLedgerValidator;
import com.hedera.services.legacy.services.stats.HederaNodeStats;
import com.hedera.services.legacy.services.utils.DefaultAccountsExporter;
import com.hedera.services.legacy.stream.RecordStream;
import com.swirlds.common.Address;
import com.swirlds.common.AddressBook;
import com.swirlds.common.Console;
import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import com.swirlds.common.PlatformStatus;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.fcmap.FCMap;
import org.ethereum.db.ServicesRepositoryRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.BDDMockito.*;
import static org.hamcrest.MatcherAssert.assertThat;

@RunWith(JUnitPlatform.class)
public class ServicesContextTest {
	private final NodeId id = new NodeId(false, 1L);

	RichInstant consensusTimeOfLastHandledTxn = RichInstant.fromJava(Instant.now());
	Platform platform;
	SequenceNumber seqNo;
	ExchangeRates midnightRates;
	MerkleNetworkContext networkCtx;
	ServicesState state;
	Cryptography crypto;
	PropertySource properties;
	PropertySources propertySources;
	FCMap<MerkleEntityId, MerkleTopic> topics;
	FCMap<MerkleEntityId, MerkleAccount> accounts;
	FCMap<MerkleBlobMeta, MerkleOptionalBlob> storage;

	@BeforeEach
	void setup() {
		topics = mock(FCMap.class);
		storage = mock(FCMap.class);
		accounts = mock(FCMap.class);
		seqNo = mock(SequenceNumber.class);
		midnightRates = mock(ExchangeRates.class);
		networkCtx = new MerkleNetworkContext(consensusTimeOfLastHandledTxn, seqNo, midnightRates);
		state = mock(ServicesState.class);
		given(state.networkCtx()).willReturn(networkCtx);
		given(state.accounts()).willReturn(accounts);
		given(state.storage()).willReturn(storage);
		given(state.topics()).willReturn(topics);
		crypto = mock(Cryptography.class);
		platform = mock(Platform.class);
		given(platform.getCryptography()).willReturn(crypto);
		properties = mock(PropertySource.class);
		propertySources = mock(PropertySources.class);
		given(propertySources.asResolvingSource()).willReturn(properties);
	}

	@Test
	public void delegatesPrimitivesToState() {
		// setup:
		InOrder inOrder = inOrder(state);

		// given:
		var subject = new ServicesContext(id, platform, state, propertySources);

		// when:
		subject.addressBook();
		var actualSeqNo = subject.seqNo();
		var actualMidnightRates = subject.midnightRates();
		var actualLastHandleTime = subject.consensusTimeOfLastHandledTxn();
		subject.topics();
		subject.storage();
		subject.accounts();

		// then:
		inOrder.verify(state).addressBook();
		assertEquals(seqNo, actualSeqNo);
		assertEquals(midnightRates, actualMidnightRates);
		assertEquals(consensusTimeOfLastHandledTxn.toJava(), actualLastHandleTime);
		inOrder.verify(state).topics();
		inOrder.verify(state).storage();
		inOrder.verify(state).accounts();
	}

	@Test
	public void hasExpectedFundingAccount() {
		given(properties.getStringProperty("ledger.funding.account")).willReturn("0.0.98");

		// when:
		ServicesContext ctx = new ServicesContext(id, platform, state, propertySources);

		// then:
		assertEquals(AccountID.newBuilder().setAccountNum(98L).build(), ctx.fundingAccount());
	}

	@Test
	public void returnsMissingValueWithoutFundingAccountProp() {
		// when:
		ServicesContext ctx = new ServicesContext(id, platform, state, propertySources);

		// then:
		assertNull(ctx.fundingAccount());
	}

	@Test
	public void hasExpectedNodeAccount() {
		// setup:
		Address address = mock(Address.class);
		AddressBook book = mock(AddressBook.class);

		given(address.getMemo()).willReturn("0.0.3");
		given(book.getAddress(1L)).willReturn(address);
		given(state.addressBook()).willReturn(book);

		// when:
		ServicesContext ctx = new ServicesContext(id, platform, state, propertySources);

		// then:
		assertEquals(ctx.address(), address);
		assertEquals(AccountID.newBuilder().setAccountNum(3L).build(), ctx.nodeAccount());
	}

	@Test
	public void canOverrideLastHandledConsensusTime() {
		// given:
		Instant dataDrivenNow = Instant.now();
		ServicesContext ctx =
				new ServicesContext(
						id,
						platform,
						state,
						propertySources);

		// when:
		ctx.updateConsensusTimeOfLastHandledTxn(dataDrivenNow);

		// then:
		assertEquals(dataDrivenNow, ctx.consensusTimeOfLastHandledTxn());
	}

	@Test
	public void hasExpectedConsole() {
		// setup:
		Console console = mock(Console.class);
		given(platform.createConsole(true)).willReturn(console);

		// when:
		ServicesContext ctx = new ServicesContext(id, platform, state, propertySources);

		// then:
		assertEquals(console, ctx.console());
		assertNull(ctx.consoleOut());
	}

	@Test
	public void hasExpectedInfrastructure() {
		// setup:
		Address address = mock(Address.class);
		AddressBook book = mock(AddressBook.class);
		given(address.getMemo()).willReturn("0.0.3");
		given(book.getAddress(1L)).willReturn(address);
		given(state.addressBook()).willReturn(book);
		given(properties.getStringProperty("hedera.recordStream.logDir")).willReturn("src/main/resources");
		GlobalFlag.getInstance().setPlatformStatus(PlatformStatus.DISCONNECTED);

		// given:
		ServicesContext ctx = new ServicesContext(id, platform, state, propertySources);

		// expect:
		assertEquals(SleepingPause.INSTANCE, ctx.pause());
		assertEquals(PlatformStatus.DISCONNECTED, ctx.platformStatus().get());
		assertEquals("record_stream_0.0.3", ctx.recordStreamThread().getName());
		assertEquals(ctx.properties(), properties);
		assertEquals(ctx.propertySources(), propertySources);
		// and expect TDD:
		assertThat(ctx.hfs(), instanceOf(TieredHederaFs.class));
		assertThat(ctx.ids(), instanceOf(SeqNoEntityIdSource.class));
		assertThat(ctx.fees(), instanceOf(UsageBasedFeeCalculator.class));
		assertThat(ctx.grpc(), instanceOf(NettyGrpcServerManager.class));
		assertThat(ctx.ledger(), instanceOf(HederaLedger.class));
		assertThat(ctx.txnCtx(), instanceOf(AwareTransactionContext.class));
		assertThat(ctx.keyOrder(), instanceOf(HederaSigningOrder.class));
		assertThat(ctx.validator(), instanceOf(ContextOptionValidator.class));
		assertThat(ctx.hcsAnswers(), instanceOf(HcsAnswers.class));
		assertThat(ctx.issEventInfo(), instanceOf(IssEventInfo.class));
		assertThat(ctx.cryptoGrpc(), instanceOf(CryptoController.class));
		assertThat(ctx.answerFlow(), instanceOf(ServiceAnswerFlow.class));
		assertThat(ctx.recordCache(), instanceOf(RecordCache.class));
		assertThat(ctx.topics(), instanceOf(FCMap.class));
		assertThat(ctx.storage(), instanceOf(FCMap.class));
		assertThat(ctx.metaAnswers(), instanceOf(MetaAnswers.class));
		assertThat(ctx.stateViews().get(), instanceOf(StateView.class));
		assertThat(ctx.fileNums(), instanceOf(FileNumbers.class));
		assertThat(ctx.accountNums(), instanceOf(AccountNumbers.class));
		assertThat(ctx.usagePrices(), instanceOf(AwareFcfsUsagePrices.class));
		assertThat(ctx.currentView(), instanceOf(StateView.class));
		assertThat(ctx.blobStore(), instanceOf(FcBlobsBytesStore.class));
		assertThat(ctx.oldExpiries(), instanceOf(Map.class));
		assertThat(ctx.syncVerifier(), instanceOf(SyncVerifier.class));
		assertThat(ctx.txnThrottling(), instanceOf(TransactionThrottling.class));
		assertThat(ctx.bucketThrottling(), instanceOf(BucketThrottling.class));
		assertThat(ctx.accountSource(), instanceOf(LedgerAccountsSource.class));
		assertThat(ctx.bytecodeDb(), instanceOf(BlobStorageSource.class));
		assertThat(ctx.cryptoAnswers(), instanceOf(CryptoAnswers.class));
		assertThat(ctx.consensusGrpc(), instanceOf(ConsensusController.class));
		assertThat(ctx.storagePersistence(), instanceOf(BlobStoragePersistence.class));
		assertThat(ctx.filesGrpc(), instanceOf(FileController.class));
		assertThat(ctx.networkGrpc(), instanceOf(NetworkController.class));
		assertThat(ctx.number(), instanceOf(EntityNumbers.class));
		assertThat(ctx.authPolicy(), instanceOf(TxnAwareAuthPolicy.class));
		assertThat(ctx.feeSchedulesManager(), instanceOf(FeeSchedulesManager.class));
		assertThat(ctx.submissionFlow(), instanceOf(TxnHandlerSubmissionFlow.class));
		assertThat(ctx.answerFunctions(), instanceOf(AnswerFunctions.class));
		assertThat(ctx.queryFeeCheck(), instanceOf(QueryFeeCheck.class));
		assertThat(ctx.queryableTopics(), instanceOf(AtomicReference.class));
		assertThat(ctx.transitionLogic(), instanceOf(TransitionLogicLookup.class));
		assertThat(ctx.precheckVerifier(), instanceOf(PrecheckVerifier.class));
		assertThat(ctx.apiPermissionsReloading(), instanceOf(ValidatingCallbackInterceptor.class));
		assertThat(ctx.applicationPropertiesReloading(), instanceOf(ValidatingCallbackInterceptor.class));
		assertThat(ctx.recordsHistorian(), instanceOf(FeePayingRecordsHistorian.class));
		assertThat(ctx.queryableAccounts(), instanceOf(AtomicReference.class));
		assertThat(ctx.txnChargingPolicy(), instanceOf(TxnFeeChargingPolicy.class));
		assertThat(ctx.txnResponseHelper(), instanceOf(TxnResponseHelper.class));
		assertThat(ctx.statusCounts(), instanceOf(ConsensusStatusCounts.class));
		assertThat(ctx.queryableStorage(), instanceOf(AtomicReference.class));
		assertThat(ctx.systemFilesManager(), instanceOf(HfsSystemFilesManager.class));
		assertThat(ctx.queryResponseHelper(), instanceOf(QueryResponseHelper.class));
		assertThat(ctx.duplicateClassifier(), instanceOf(TxnAwareDuplicateClassifier.class));
		assertThat(ctx.solidityLifecycle(), instanceOf(SolidityLifecycle.class));
		assertThat(ctx.charging(), instanceOf(ItemizableFeeCharging.class));
		assertThat(ctx.repository(), instanceOf(ServicesRepositoryRoot.class));
		assertThat(ctx.newPureRepo(), instanceOf(Supplier.class));
		assertThat(ctx.exchangeRatesManager(), instanceOf(TxnAwareRatesManager.class));
		assertThat(ctx.lookupRetryingKeyOrder(), instanceOf(HederaSigningOrder.class));
		assertThat(ctx.nodeDuplicateClassifier(), instanceOf(PerNodeDuplicateClassifier.class));
		assertThat(ctx.soliditySigsVerifier(), instanceOf(TxnAwareSoliditySigsVerifier.class));
		// and expect legacy:
		assertThat(ctx.exchange(), instanceOf(DefaultHbarCentExchange.class));
		assertThat(ctx.txns(), instanceOf(TransactionHandler.class));
		assertThat(ctx.stats(), instanceOf(HederaNodeStats.class));
		assertThat(ctx.contracts(), instanceOf(SmartContractRequestHandler.class));
		assertThat(ctx.freezeGrpc(), instanceOf(FreezeServiceImpl.class));
		assertThat(ctx.contractsGrpc(), instanceOf(SmartContractServiceImpl.class));
		assertThat(ctx.propertySanitizer(), instanceOf(DefaultPropertySanitizer.class));
		assertThat(ctx.stateMigrations(), instanceOf(DefaultStateMigrations.class));
		assertThat(ctx.ledgerValidator(), instanceOf(DefaultLedgerValidator.class));
		assertThat(ctx.recordStream(), instanceOf(RecordStream.class));
		assertThat(ctx.accountsExporter(), instanceOf(DefaultAccountsExporter.class));
		assertThat(ctx.systemAccountsCreator(), instanceOf(DefaultSystemAccountsCreator.class));
		assertThat(ctx.balancesExporter(), instanceOf(DefaultBalancesExporter.class));
		assertThat(ctx.exemptions(), instanceOf(DefaultFeeExemptions.class));
		assertThat(ctx.freeze(), instanceOf(FreezeHandler.class));
		assertThat(ctx.logic(), instanceOf(AwareProcessLogic.class));

		// cleanup:
		GlobalFlag.getInstance().setPlatformStatus(null);
	}
}
