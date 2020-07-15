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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.services.ServicesMain;
import com.hedera.services.ServicesState;
import com.hedera.services.config.AccountNumbers;
import com.hedera.services.config.EntityNumbers;
import com.hedera.services.config.FileNumbers;
import com.hedera.services.context.domain.trackers.ConsensusStatusCounts;
import com.hedera.services.context.domain.trackers.IssEventInfo;
import com.hedera.services.state.merkle.MerkleTopic;
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.context.properties.PropertySanitizer;
import com.hedera.services.context.properties.StandardizedPropertySources;
import com.hedera.services.contracts.execution.SolidityLifecycle;
import com.hedera.services.contracts.execution.SoliditySigsVerifier;
import com.hedera.services.contracts.execution.TxnAwareSoliditySigsVerifier;
import com.hedera.services.contracts.persistence.BlobStoragePersistence;
import com.hedera.services.fees.FeeCalculator;
import com.hedera.services.fees.FeeExemptions;
import com.hedera.services.fees.HbarCentExchange;
import com.hedera.services.fees.calculation.AwareFcfsUsagePrices;
import com.hedera.services.fees.calculation.UsagePricesProvider;
import com.hedera.services.fees.calculation.UsageBasedFeeCalculator;
import com.hedera.services.fees.calculation.consensus.queries.GetTopicInfoResourceUsage;
import com.hedera.services.fees.calculation.consensus.txns.CreateTopicResourceUsage;
import com.hedera.services.fees.calculation.consensus.txns.DeleteTopicResourceUsage;
import com.hedera.services.fees.calculation.consensus.txns.SubmitMessageResourceUsage;
import com.hedera.services.fees.calculation.consensus.txns.UpdateTopicResourceUsage;
import com.hedera.services.fees.calculation.contract.txns.ContractCallResourceUsage;
import com.hedera.services.fees.calculation.contract.txns.ContractCreateResourceUsage;
import com.hedera.services.fees.calculation.contract.txns.ContractDeleteResourceUsage;
import com.hedera.services.fees.calculation.contract.txns.ContractUpdateResourceUsage;
import com.hedera.services.fees.calculation.crypto.queries.GetAccountInfoResourceUsage;
import com.hedera.services.fees.calculation.crypto.queries.GetAccountRecordsResourceUsage;
import com.hedera.services.fees.calculation.crypto.queries.GetTxnRecordResourceUsage;
import com.hedera.services.fees.calculation.crypto.txns.CryptoCreateResourceUsage;
import com.hedera.services.fees.calculation.crypto.txns.CryptoDeleteResourceUsage;
import com.hedera.services.fees.calculation.crypto.txns.CryptoTransferResourceUsage;
import com.hedera.services.fees.calculation.crypto.txns.CryptoUpdateResourceUsage;
import com.hedera.services.fees.calculation.file.queries.GetFileContentsResourceUsage;
import com.hedera.services.fees.calculation.file.queries.GetFileInfoResourceUsage;
import com.hedera.services.fees.calculation.file.txns.FileAppendResourceUsage;
import com.hedera.services.fees.calculation.file.txns.FileCreateResourceUsage;
import com.hedera.services.fees.calculation.file.txns.FileDeleteResourceUsage;
import com.hedera.services.fees.calculation.file.txns.FileUpdateResourceUsage;
import com.hedera.services.fees.calculation.file.txns.SystemDeleteFileResourceUsage;
import com.hedera.services.fees.calculation.file.txns.SystemUndeleteFileResourceUsage;
import com.hedera.services.fees.calculation.meta.queries.GetVersionInfoResourceUsage;
import com.hedera.services.fees.calculation.system.txns.FreezeResourceUsage;
import com.hedera.services.fees.charging.ItemizableFeeCharging;
import com.hedera.services.fees.charging.TxnFeeChargingPolicy;
import com.hedera.services.files.DataMapFactory;
import com.hedera.services.files.ExpiryMapFactory;
import com.hedera.services.files.FileUpdateInterceptor;
import com.hedera.services.files.HederaFs;
import com.hedera.services.files.MetadataMapFactory;
import com.hedera.services.files.TieredHederaFs;
import com.hedera.services.files.interceptors.ConfigListUtils;
import com.hedera.services.files.interceptors.FeeSchedulesManager;
import com.hedera.services.files.interceptors.TxnAwareAuthPolicy;
import com.hedera.services.files.interceptors.TxnAwareRatesManager;
import com.hedera.services.files.interceptors.ValidatingCallbackInterceptor;
import com.hedera.services.files.store.FcBlobsBytesStore;
import com.hedera.services.grpc.GrpcServerManager;
import com.hedera.services.grpc.NettyGrpcServerManager;
import com.hedera.services.grpc.controllers.ConsensusController;
import com.hedera.services.grpc.controllers.CryptoController;
import com.hedera.services.grpc.controllers.FileController;
import com.hedera.services.grpc.controllers.NetworkController;
import com.hedera.services.keys.StandardSyncActivationCheck;
import com.hedera.services.ledger.accounts.FCMapBackingAccounts;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.ledger.ids.SeqNoEntityIdSource;
import com.hedera.services.ledger.properties.ChangeSummaryManager;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.ledger.TransactionalLedger;
import com.hedera.services.queries.answering.ServiceAnswerFlow;
import com.hedera.services.queries.consensus.GetTopicInfoAnswer;
import com.hedera.services.queries.consensus.HcsAnswers;
import com.hedera.services.queries.file.FileAnswers;
import com.hedera.services.queries.file.GetFileContentsAnswer;
import com.hedera.services.queries.file.GetFileInfoAnswer;
import com.hedera.services.queries.meta.GetVersionInfoAnswer;
import com.hedera.services.queries.validation.QueryFeeCheck;
import com.hedera.services.state.initialization.HfsSystemFilesManager;
import com.hedera.services.state.initialization.SystemFilesManager;
import com.hedera.services.throttling.BucketThrottling;
import com.hedera.services.throttling.ThrottlingPropsBuilder;
import com.hedera.services.throttling.TransactionThrottling;

import static com.hedera.services.throttling.bucket.BucketConfig.bucketsIn;
import static com.hedera.services.throttling.bucket.BucketConfig.namedIn;
import com.hedera.services.txns.ProcessLogic;
import com.hedera.services.txns.SubmissionFlow;
import com.hedera.services.txns.TransitionLogicLookup;
import com.hedera.services.txns.consensus.SubmitMessageTransitionLogic;
import com.hedera.services.txns.consensus.TopicCreateTransitionLogic;
import com.hedera.services.txns.consensus.TopicDeleteTransitionLogic;
import com.hedera.services.txns.consensus.TopicUpdateTransitionLogic;
import com.hedera.services.txns.crypto.CryptoCreateTransitionLogic;
import com.hedera.services.txns.crypto.CryptoDeleteTransitionLogic;
import com.hedera.services.txns.crypto.CryptoTransferTransitionLogic;
import com.hedera.services.txns.crypto.CryptoUpdateTransitionLogic;
import com.hedera.services.txns.diligence.CountingDuplicateClassifier;
import com.hedera.services.txns.diligence.DuplicateClassifier;
import com.hedera.services.txns.diligence.NodeDuplicateClassifier;
import com.hedera.services.txns.diligence.PerNodeDuplicateClassifier;
import com.hedera.services.txns.diligence.ScopedDuplicateClassifier;
import com.hedera.services.txns.diligence.TxnAwareDuplicateClassifier;
import com.hedera.services.txns.file.FileAppendTransitionLogic;
import com.hedera.services.txns.file.FileCreateTransitionLogic;
import com.hedera.services.txns.file.FileDeleteTransitionLogic;
import com.hedera.services.txns.file.FileSysDelTransitionLogic;
import com.hedera.services.txns.file.FileSysUndelTransitionLogic;
import com.hedera.services.txns.file.FileUpdateTransitionLogic;
import com.hedera.services.txns.submission.TxnHandlerSubmissionFlow;
import com.hedera.services.txns.submission.TxnResponseHelper;
import com.hedera.services.txns.validation.ContextOptionValidator;
import com.hedera.services.txns.validation.BasicPrecheck;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.queries.answering.AnswerFunctions;
import com.hedera.services.queries.AnswerFlow;
import com.hedera.services.queries.answering.QueryResponseHelper;
import com.hedera.services.queries.crypto.CryptoAnswers;
import com.hedera.services.queries.crypto.GetAccountBalanceAnswer;
import com.hedera.services.queries.crypto.GetAccountInfoAnswer;
import com.hedera.services.queries.crypto.GetAccountRecordsAnswer;
import com.hedera.services.queries.crypto.GetLiveHashAnswer;
import com.hedera.services.queries.crypto.GetStakersAnswer;
import com.hedera.services.queries.meta.GetFastTxnRecordAnswer;
import com.hedera.services.queries.meta.GetTxnReceiptAnswer;
import com.hedera.services.queries.meta.GetTxnRecordAnswer;
import com.hedera.services.queries.meta.MetaAnswers;
import com.hedera.services.records.AccountRecordsHistorian;
import com.hedera.services.records.FeePayingRecordsHistorian;
import com.hedera.services.records.EarliestRecordExpiry;
import com.hedera.services.records.RecordCache;
import com.hedera.services.records.RecordCacheFactory;
import com.hedera.services.sigs.metadata.SigMetadataLookup;
import com.hedera.services.sigs.order.HederaSigningOrder;
import com.hedera.services.sigs.sourcing.DefaultSigBytesProvider;
import com.hedera.services.sigs.verification.PrecheckKeyReqs;
import com.hedera.services.sigs.verification.PrecheckVerifier;
import com.hedera.services.sigs.verification.SyncVerifier;
import com.hedera.services.state.exports.BalancesExporter;
import com.hedera.services.state.initialization.SystemAccountsCreator;
import com.hedera.services.state.validation.LedgerValidator;
import com.hedera.services.state.exports.AccountsExporter;
import com.hedera.services.utils.EntityIdUtils;

import static com.hedera.services.contracts.sources.AddressKeyedMapFactory.bytecodeMapFrom;
import static com.hedera.services.contracts.sources.AddressKeyedMapFactory.storageMapFrom;
import static com.hedera.services.ledger.ids.ExceptionalEntityIdSource.NOOP_ID_SOURCE;
import static com.hedera.services.records.NoopRecordsHistorian.NOOP_RECORDS_HISTORIAN;
import static com.hedera.services.txns.diligence.NoopDuplicateClassifier.NOOP_DUPLICATE_CLASSIFIER;
import static com.hedera.services.utils.MiscUtils.lookupInCustomStore;
import com.hedera.services.utils.Pause;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.fee.CryptoFeeBuilder;
import com.hederahashgraph.fee.FileFeeBuilder;
import com.hederahashgraph.fee.SmartContractFeeBuilder;
import com.hedera.services.legacy.config.PropertiesLoader;
import com.hedera.services.legacy.handler.FreezeHandler;
import com.hedera.services.legacy.handler.SmartContractRequestHandler;
import com.hedera.services.legacy.handler.TransactionHandler;
import com.hedera.services.legacy.logic.CustomProperties;
import com.hedera.services.legacy.netty.CryptoServiceInterceptor;
import com.hedera.services.legacy.netty.NettyServerManager;
import com.hedera.services.contracts.sources.LedgerAccountsSource;
import com.hedera.services.contracts.sources.BlobStorageSource;
import com.hedera.services.legacy.service.FreezeServiceImpl;
import com.hedera.services.legacy.service.GlobalFlag;
import com.hedera.services.legacy.service.SmartContractServiceImpl;
import com.hedera.services.legacy.services.context.DefaultCurrentPlatformStatus;
import com.hedera.services.state.submerkle.ExchangeRates;
import com.hedera.services.state.submerkle.SequenceNumber;
import com.hedera.services.context.properties.PropertySources;
import com.hedera.services.state.migration.DefaultStateMigrations;
import com.hedera.services.legacy.services.context.properties.DefaultPropertySanitizer;
import com.hedera.services.legacy.services.fees.DefaultFeeExemptions;
import com.hedera.services.legacy.services.fees.DefaultHbarCentExchange;
import com.hedera.services.legacy.services.state.AwareProcessLogic;
import com.hedera.services.legacy.services.state.export.DefaultBalancesExporter;
import com.hedera.services.legacy.services.state.initialization.DefaultSystemAccountsCreator;
import com.hedera.services.state.migration.StateMigrations;
import com.hedera.services.utils.SleepingPause;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleBlobMeta;
import com.hedera.services.state.merkle.MerkleOptionalBlob;
import com.hedera.services.legacy.services.state.validation.DefaultLedgerValidator;
import com.hedera.services.legacy.services.stats.HederaNodeStats;
import com.hedera.services.legacy.services.utils.DefaultAccountsExporter;
import com.hedera.services.legacy.stream.RecordStream;
import com.swirlds.common.Address;
import com.swirlds.common.AddressBook;
import com.swirlds.common.Console;
import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import com.swirlds.fcmap.FCMap;
import org.ethereum.core.AccountState;
import org.ethereum.datasource.Source;
import org.ethereum.datasource.StoragePersistence;
import org.ethereum.db.ServicesRepositoryRoot;
import com.hedera.services.context.properties.PropertySource;

import java.io.PrintStream;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hedera.services.files.interceptors.ConfigListUtils.uncheckedParse;
import static com.hedera.services.files.interceptors.PureRatesValidation.isNormalIntradayChange;
import static com.hedera.services.sigs.metadata.DelegatingSigMetadataLookup.defaultAccountRetryingLookupsFor;
import static com.hedera.services.sigs.metadata.DelegatingSigMetadataLookup.defaultLookupsFor;
import static com.hedera.services.sigs.utils.PrecheckUtils.queryPaymentTestFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hedera.services.legacy.config.PropertiesLoader.populateAPIPropertiesWithProto;
import static com.hedera.services.legacy.config.PropertiesLoader.populateApplicationPropertiesWithProto;
import static io.grpc.ServerInterceptors.intercept;
import static java.util.stream.Collectors.toMap;

/**
 * Provide a trivial implementation of the inversion-of-control pattern,
 * isolating secondary responsibilities of dependency creation and
 * injection in a single component.
 *
 * @author Michael Tinker
 */
public class ServicesContext {
	private static final CustomProperties IGNORED_API_PERMISSION_PROPS = null;

	/* Injected dependencies. */
	private final NodeId id;
	private final Platform platform;
	private ServicesState state;
	private final PropertySources propertySources;

	/* Context-sensitive singletons. */
	private Thread recordStreamThread;
	private Address address;
	private Console console;
	private HederaFs hfs;
	private StateView currentView;
	private AccountID accountId;
	private AnswerFlow answerFlow;
	private HcsAnswers hcsAnswers;
	private FileNumbers fileNums;
	private FileAnswers fileAnswers;
	private MetaAnswers metaAnswers;
	private RecordCache recordCache;
	private HederaLedger ledger;
	private SyncVerifier syncVerifier;
	private IssEventInfo issEventInfo;
	private ProcessLogic logic;
	private RecordStream recordStream;
	private QueryFeeCheck queryFeeCheck;
	private FeeCalculator fees;
	private FeeExemptions exemptions;
	private EntityNumbers number;
	private FreezeHandler freeze;
	private CryptoAnswers cryptoAnswers;
	private AccountNumbers accountNums;
	private SubmissionFlow submissionFlow;
	private PropertySource properties;
	private EntityIdSource ids;
	private FileController fileGrpc;
	private AnswerFunctions answerFunctions;
	private OptionValidator validator;
	private LedgerValidator ledgerValidator;
	private HederaNodeStats stats;
	private CryptoController cryptoGrpc;
	private BucketThrottling bucketThrottling;
	private HbarCentExchange exchange;
	private PrecheckVerifier precheckVerifier;
	private BalancesExporter balancesExporter;
	private SolidityLifecycle solidityLifecycle;
	private NetworkController networkGrpc;
	private GrpcServerManager grpc;
	private FreezeServiceImpl freezeGrpc;
	private TxnResponseHelper txnResponseHelper;
	private Map<FileID, Long> oldExpiries;
	private TransactionContext txnCtx;
	private BlobStorageSource bytecodeDb;
	private TxnAwareAuthPolicy authPolicy;
	private TransactionHandler txns;
	private HederaSigningOrder keyOrder;
	private HederaSigningOrder lookupRetryingKeyOrder;
	private StoragePersistence storagePersistence;
	private ConsensusController consensusGrpc;
	private QueryResponseHelper queryResponseHelper;
	private UsagePricesProvider usagePrices;
	private Supplier<StateView> stateViews;
	private FeeSchedulesManager feeSchedulesManager;
	private Map<String, byte[]> blobStore;
	private TxnFeeChargingPolicy txnChargingPolicy;
	private TxnAwareRatesManager exchangeRatesManager;
	private LedgerAccountsSource accountSource;
	private TransitionLogicLookup transitionLogic;
	private TransactionThrottling txnThrottling;
	private ConsensusStatusCounts statusCounts;
	private HfsSystemFilesManager systemFilesManager;
	private ItemizableFeeCharging itemizableFeeCharging;
	private ServicesRepositoryRoot repository;
	private NodeDuplicateClassifier nodeDuplicateClassifier;
	private AccountRecordsHistorian recordsHistorian;
	private SmartContractServiceImpl contractsGrpc;
	private SmartContractRequestHandler contracts;
	private TxnAwareDuplicateClassifier duplicateClassifier;
	private TxnAwareSoliditySigsVerifier soliditySigsVerifier;
	private ValidatingCallbackInterceptor apiPermissionsReloading;
	private ValidatingCallbackInterceptor applicationPropertiesReloading;
	private Supplier<ServicesRepositoryRoot> newPureRepo;
	private AtomicReference<FCMap<MerkleEntityId, MerkleTopic>> queryableTopics;
	private AtomicReference<FCMap<MerkleEntityId, MerkleAccount>> queryableAccounts;
	private AtomicReference<FCMap<MerkleBlobMeta, MerkleOptionalBlob>> queryableStorage;

	/* Context-free infrastructure. */
	private static Pause pause;
	private static StateMigrations stateMigrations;
	private static AccountsExporter accountsExporter;
	private static PropertySanitizer propertySanitizer;
	private static SystemAccountsCreator systemAccountsCreator;
	private static CurrentPlatformStatus platformStatus;
	static {
		pause = SleepingPause.INSTANCE;
		platformStatus = new DefaultCurrentPlatformStatus();
		stateMigrations = new DefaultStateMigrations(SleepingPause.INSTANCE);
		accountsExporter = new DefaultAccountsExporter();
		propertySanitizer = new DefaultPropertySanitizer();
		systemAccountsCreator = new DefaultSystemAccountsCreator();
	}

	public ServicesContext(
			NodeId id,
			Platform platform,
			ServicesState state,
			PropertySources propertySources
	) {
		this.id = id;
		this.platform = platform;
		this.state = state;
		this.propertySources = propertySources;
	}

	public LedgerValidator ledgerValidator() {
		if (ledgerValidator == null) {
			ledgerValidator = new DefaultLedgerValidator();
		}
		return ledgerValidator;
	}

	public IssEventInfo issEventInfo() {
		if (issEventInfo == null) {
			issEventInfo = new IssEventInfo(properties());
		}
		return issEventInfo;
	}

	public Map<String, byte[]> blobStore() {
		if (blobStore == null) {
			blobStore = new FcBlobsBytesStore(MerkleOptionalBlob::new, storage());
		}
		return blobStore;
	}

	public Supplier<StateView> stateViews() {
		if (stateViews == null) {
			stateViews = () -> new StateView(
					queryableTopics().get(),
					queryableAccounts().get(),
					queryableStorage().get());
		}
		return stateViews;
	}

	public StateView currentView() {
		if (currentView == null) {
			currentView = new StateView(topics(), accounts(), storage());
		}
		return currentView;
	}

	public FileNumbers fileNums() {
		if (fileNums == null) {
			fileNums = new FileNumbers(properties());
		}
		return fileNums;
	}

	public AccountNumbers accountNums() {
		if (accountNums == null) {
			accountNums = new AccountNumbers(properties());
		}
		return accountNums;
	}

	public TxnResponseHelper txnResponseHelper() {
		if (txnResponseHelper == null) {
			txnResponseHelper = new TxnResponseHelper(submissionFlow(), stats());
		}
		return txnResponseHelper;
	}

	public TransactionThrottling txnThrottling() {
		if (txnThrottling == null) {
			txnThrottling = new TransactionThrottling(bucketThrottling());
		}
		return txnThrottling;
	}

	public BucketThrottling bucketThrottling() {
		if (bucketThrottling == null) {
			bucketThrottling = new BucketThrottling(
					addressBook(),
					properties(),
					props -> bucketsIn(props).stream().collect(toMap(Function.identity(), b -> namedIn(props, b))),
					ThrottlingPropsBuilder::withPrioritySource);
		}
		return bucketThrottling;
	}

	public ItemizableFeeCharging charging() {
		if (itemizableFeeCharging == null) {
			itemizableFeeCharging = new ItemizableFeeCharging(exemptions(), properties());
		}
		return itemizableFeeCharging;
	}

	public SubmissionFlow submissionFlow() {
		if (submissionFlow == null) {
			submissionFlow = new TxnHandlerSubmissionFlow(platform(), txns(), transitionLogic());
		}
		return submissionFlow;
	}

	public QueryResponseHelper queryResponseHelper() {
		if (queryResponseHelper == null) {
			queryResponseHelper = new QueryResponseHelper(answerFlow(), stats());
		}
		return queryResponseHelper;
	}

	public FileAnswers fileAnswers() {
		if (fileAnswers == null) {
			fileAnswers = new FileAnswers(
					new GetFileInfoAnswer(validator()),
					new GetFileContentsAnswer(validator())
			);
		}
		return fileAnswers;
	}

	public HcsAnswers hcsAnswers() {
		if (hcsAnswers == null) {
			hcsAnswers = new HcsAnswers(
					new GetTopicInfoAnswer(validator())
			);
		}
		return hcsAnswers;
	}

	public MetaAnswers metaAnswers() {
		if (metaAnswers == null) {
			metaAnswers = new MetaAnswers(
					new GetTxnRecordAnswer(recordCache(), validator(), answerFunctions()),
					new GetTxnReceiptAnswer(recordCache()),
					new GetVersionInfoAnswer(properties()),
					new GetFastTxnRecordAnswer()
			);
		}
		return metaAnswers;
	}

	public EntityNumbers number() {
		if (number == null) {
			number = new EntityNumbers(fileNums(), accountNums());
		}
		return number;
	}

	public CryptoAnswers cryptoAnswers() {
		if (cryptoAnswers == null) {
			cryptoAnswers = new CryptoAnswers(
					new GetLiveHashAnswer(),
					new GetStakersAnswer(),
					new GetAccountInfoAnswer(validator()),
					new GetAccountBalanceAnswer(validator()),
					new GetAccountRecordsAnswer(answerFunctions(), validator())
			);
		}
		return cryptoAnswers;
	}

	public AnswerFunctions answerFunctions() {
		if (answerFunctions == null) {
			answerFunctions = new AnswerFunctions();
		}
		return answerFunctions;
	}

	public QueryFeeCheck queryFeeCheck() {
		if (queryFeeCheck == null) {
			queryFeeCheck = new QueryFeeCheck(accounts());
		}
		return queryFeeCheck;
	}

	public FeeCalculator fees() {
		if (fees == null) {
			FileFeeBuilder fileFees = new FileFeeBuilder();
			CryptoFeeBuilder cryptoFees = new CryptoFeeBuilder();
			SmartContractFeeBuilder contractFees = new SmartContractFeeBuilder();

			fees = new UsageBasedFeeCalculator(
					properties(),
					exchange(),
					usagePrices(),
					List.of(
							/* Crypto */
							new CryptoCreateResourceUsage(cryptoFees),
							new CryptoDeleteResourceUsage(cryptoFees),
							new CryptoUpdateResourceUsage(cryptoFees),
							new CryptoTransferResourceUsage(cryptoFees),
							/* Contract */
							new ContractCallResourceUsage(contractFees),
							new ContractCreateResourceUsage(contractFees),
							new ContractDeleteResourceUsage(contractFees),
							new ContractUpdateResourceUsage(contractFees),
							/* File */
							new FileCreateResourceUsage(fileFees),
							new FileDeleteResourceUsage(fileFees),
							new FileUpdateResourceUsage(),
							new FileAppendResourceUsage(fileFees),
							new SystemDeleteFileResourceUsage(fileFees),
							new SystemUndeleteFileResourceUsage(fileFees),
							/* Consensus */
							new CreateTopicResourceUsage(),
							new UpdateTopicResourceUsage(),
							new DeleteTopicResourceUsage(),
							new SubmitMessageResourceUsage(),
							/* System */
							new FreezeResourceUsage()
					),
					List.of(
							/* Meta */
							new GetVersionInfoResourceUsage(),
							new GetTxnRecordResourceUsage(recordCache(), answerFunctions(), cryptoFees),
							/* Crypto */
							new GetAccountInfoResourceUsage(cryptoFees),
							new GetAccountRecordsResourceUsage(answerFunctions(), cryptoFees),
							/* File */
							new GetFileInfoResourceUsage(fileFees),
							new GetFileContentsResourceUsage(fileFees),
							/* Consensus */
							new GetTopicInfoResourceUsage()
					)
			);
		}
		return fees;
	}

	public AnswerFlow answerFlow() {
		if (answerFlow == null) {
			answerFlow = new ServiceAnswerFlow(
					platform(),
					fees(),
					txns(),
					stateViews(),
					usagePrices(),
					bucketThrottling());
		}
		return answerFlow;
	}

	public HederaSigningOrder keyOrder() {
		if (keyOrder == null) {
			SigMetadataLookup lookups = defaultLookupsFor(hfs(), accounts(), topics());
			keyOrder = new HederaSigningOrder(number(), lookups);
		}
		return keyOrder;
	}

	public StoragePersistence storagePersistence() {
		if (storagePersistence == null) {
			storagePersistence = new BlobStoragePersistence(storageMapFrom(blobStore()));
		}
		return storagePersistence;
	}

	public HederaSigningOrder lookupRetryingKeyOrder() {
		if (lookupRetryingKeyOrder == null) {
			SigMetadataLookup lookups =
					defaultAccountRetryingLookupsFor(hfs(), properties(), stats(), accounts(), topics());
			lookupRetryingKeyOrder = new HederaSigningOrder(number(), lookups);
		}
		return lookupRetryingKeyOrder;
	}

	public SyncVerifier syncVerifier() {
		if (syncVerifier == null) {
			syncVerifier = platform().getCryptography()::verifySync;
		}
		return syncVerifier;
	}

	public PrecheckVerifier precheckVerifier() {
		if (precheckVerifier == null) {
			Predicate<TransactionBody> isQueryPayment = queryPaymentTestFor(nodeAccount());
			PrecheckKeyReqs reqs = new PrecheckKeyReqs(keyOrder(), lookupRetryingKeyOrder(), isQueryPayment);
			precheckVerifier = new PrecheckVerifier(syncVerifier(), reqs, DefaultSigBytesProvider.DEFAULT_SIG_BYTES);
		}
		return precheckVerifier;
	}

	public PrintStream consoleOut() {
		return Optional.ofNullable(console()).map(c -> c.out).orElse(null);
	}

	public CurrentPlatformStatus platformStatus() {
		return platformStatus;
	}

	public BalancesExporter balancesExporter() {
		if (balancesExporter == null) {
			balancesExporter = new DefaultBalancesExporter(platform, addressBook());
		}
		return balancesExporter;
	}

	public Map<FileID, Long> oldExpiries() {
		if (oldExpiries == null) {
			oldExpiries = ExpiryMapFactory.expiryMapFrom(blobStore());
		}
		return oldExpiries;
	}

	public HederaFs hfs() {
		if (hfs == null) {
			hfs = new TieredHederaFs(
					ids(),
					properties(),
					txnCtx()::consensusTime,
					DataMapFactory.dataMapFrom(blobStore())	,
					MetadataMapFactory.metaMapFrom(blobStore()));
			hfs.register(authPolicy());
			hfs.register(feeSchedulesManager());
			hfs.register(exchangeRatesManager());
			hfs.register(apiPermissionsReloading());
			hfs.register(applicationPropertiesReloading());
		}
		return hfs;
	}

	public SoliditySigsVerifier soliditySigsVerifier() {
		if (soliditySigsVerifier == null) {
			soliditySigsVerifier = new TxnAwareSoliditySigsVerifier(
					syncVerifier(),
					txnCtx(),
					StandardSyncActivationCheck::allKeysAreActive,
					accounts());
		}
		return soliditySigsVerifier;
	}

	public FileUpdateInterceptor applicationPropertiesReloading() {
		if (applicationPropertiesReloading == null) {
			applicationPropertiesReloading = new ValidatingCallbackInterceptor(
				0,
				"files.applicationProperties.num",
					properties(),
					contents -> {
						var config = uncheckedParse(contents);
						((StandardizedPropertySources)propertySources()).updateThrottlePropsFrom(config);
						populateApplicationPropertiesWithProto(config);
					},
					ConfigListUtils::isConfigList
			);
		}
		return applicationPropertiesReloading;
	}

	public FileUpdateInterceptor apiPermissionsReloading() {
		if (apiPermissionsReloading == null) {
			apiPermissionsReloading = new ValidatingCallbackInterceptor(
					0,
					"files.apiPermissions.num",
					properties(),
					contents -> populateAPIPropertiesWithProto(uncheckedParse(contents)),
					ConfigListUtils::isConfigList
			);
		}
		return apiPermissionsReloading;
	}

	public TransitionLogicLookup transitionLogic() {
		if (transitionLogic == null) {
			transitionLogic = new TransitionLogicLookup(
					/* ---- CRYPTO ---- */
					new CryptoCreateTransitionLogic(ledger(), validator(), txnCtx()),
					new CryptoUpdateTransitionLogic(ledger(), validator(), txnCtx()),
					new CryptoDeleteTransitionLogic(ledger(), txnCtx()),
					new CryptoTransferTransitionLogic(ledger(), validator(), txnCtx()),
					/* ----- FILE ---- */
					new FileUpdateTransitionLogic(hfs(), number(), validator(), txnCtx()),
					new FileCreateTransitionLogic(hfs(), validator(), txnCtx()),
					new FileDeleteTransitionLogic(hfs(), txnCtx()),
					new FileAppendTransitionLogic(hfs(), txnCtx()),
					new FileSysDelTransitionLogic(hfs(), oldExpiries(), txnCtx()),
					new FileSysUndelTransitionLogic(hfs(), oldExpiries(), txnCtx()),
					/* ----- CONSENSUS ---- */
					new TopicCreateTransitionLogic(accounts(), topics(), ids(), validator(), txnCtx()),
					new TopicUpdateTransitionLogic(accounts(), topics(), validator(), txnCtx()),
					new TopicDeleteTransitionLogic(topics(), validator(), txnCtx()),
					new SubmitMessageTransitionLogic(topics(), validator(), txnCtx()));
		}
		return transitionLogic;
	}

	public EntityIdSource ids() {
		if (ids == null) {
			ids = new SeqNoEntityIdSource(seqNo());
		}
		return ids;
	}

	public TransactionContext txnCtx() {
		if (txnCtx == null) {
			txnCtx = new AwareTransactionContext(this);
		}
		return txnCtx;
	}

	public RecordCache recordCache() {
		if (recordCache == null) {
			recordCache = new RecordCache(new RecordCacheFactory(properties()).getRecordCache());
		}
		return recordCache;
	}

	public AccountRecordsHistorian recordsHistorian() {
		if (recordsHistorian == null) {
			final EnumSet<ResponseCodeEnum> NON_QUERYABLE_RECORD_STATUSES = EnumSet.of(
					INVALID_NODE_ACCOUNT,
					DUPLICATE_TRANSACTION,
					INVALID_PAYER_SIGNATURE
			);
			Predicate<TransactionContext> isScopedRecordQueryable = txnCtx ->
				!NON_QUERYABLE_RECORD_STATUSES.contains(txnCtx.status());

			BlockingQueue<EarliestRecordExpiry> pQ = new PriorityBlockingQueue<>();
			recordsHistorian = new FeePayingRecordsHistorian(
					recordCache(),
					fees(),
					properties(),
					txnCtx(),
					charging(),
					accounts(),
					isScopedRecordQueryable,
					pQ);
		}
		return recordsHistorian;
	}

	public FeeExemptions exemptions() {
		if (exemptions == null) {
			exemptions = new DefaultFeeExemptions();
		}
		return exemptions;
	}

	public HbarCentExchange exchange() {
		if (exchange == null) {
			exchange = new DefaultHbarCentExchange(txnCtx());
		}
		return exchange;
	}

	public HederaLedger ledger() {
		if (ledger == null) {
			TransactionalLedger<AccountID, AccountProperty, MerkleAccount> delegate = new TransactionalLedger<>(
					AccountProperty.class,
					MerkleAccount::new,
					new FCMapBackingAccounts(accounts()),
					new ChangeSummaryManager<>()
			);
			delegate.setKeyComparator(HederaLedger.ACCOUNT_ID_COMPARATOR);
			ledger = new HederaLedger(ids(), recordsHistorian(), duplicateClassifier(), delegate);
		}
		return ledger;
	}

	public OptionValidator validator() {
		if (validator == null) {
			validator = new ContextOptionValidator(ledger(), properties(), txnCtx());
		}
		return validator;
	}

	public ProcessLogic logic() {
		if (logic == null) {
			logic = new AwareProcessLogic(this);
		}
		return logic;
	}

	public FreezeHandler freeze() {
		if (freeze == null) {
			freeze = new FreezeHandler(hfs(), platform);
		}
		return freeze;
	}

	public Thread recordStreamThread() {
		if (recordStreamThread == null) {
			recordStreamThread = new Thread(recordStream());
			recordStreamThread.setName("record_stream_" + address().getMemo());
		}
		return recordStreamThread;
	}

	public void updateFeature() {
		if (freeze != null) {
			//if multiple nodes running on same JVM only first node run update feature
			String OS = System.getProperty("os.name").toLowerCase();

			//for test purpose, on local test only node 0 runs the script
			if (OS.indexOf("mac") >= 0) {
				if (platform.getSelfId().getId() == 0){
					freeze.handleUpdateFeature();
				}
			} else {
				freeze.handleUpdateFeature();
			}
		}
	}

	public RecordStream recordStream() {
		if (recordStream == null) {
			recordStream = new RecordStream(
					platform,
					stats(),
					nodeAccount(),
					properties().getStringProperty("hedera.recordStream.logDir"),
					properties().getLongProperty("hedera.recordStream.logPeriod"));
		}
		return recordStream;
	}

	public FileUpdateInterceptor exchangeRatesManager() {
		if (exchangeRatesManager == null) {
			exchangeRatesManager = new TxnAwareRatesManager(
					fileNums(),
					accountNums(),
					properties(),
					txnCtx(),
					midnightRates(),
					GlobalFlag.getInstance()::setExchangeRateSet,
					limitPercent -> (base, proposed) -> isNormalIntradayChange(base, proposed, limitPercent));
		}
		return exchangeRatesManager;
	}

	public FileUpdateInterceptor feeSchedulesManager() {
		if (feeSchedulesManager == null) {
			feeSchedulesManager = new FeeSchedulesManager(fees(), properties());
		}
		return feeSchedulesManager;
	}

	public FileUpdateInterceptor authPolicy() {
		if (authPolicy == null) {
			authPolicy = new TxnAwareAuthPolicy(fileNums(), accountNums(), properties(), txnCtx());
		}
		return authPolicy;
	}

	public FreezeServiceImpl freezeGrpc() {
		if (freezeGrpc == null) {
			freezeGrpc = new FreezeServiceImpl(platform, txns());
		}
		return freezeGrpc;
	}

	public NetworkController networkGrpc() {
		if (networkGrpc == null) {
			networkGrpc = new NetworkController(metaAnswers(), queryResponseHelper());
		}
		return networkGrpc;
	}

	public FileController filesGrpc() {
		if (fileGrpc == null) {
			fileGrpc = new FileController(fileAnswers(), txnResponseHelper(), queryResponseHelper());
		}
		return fileGrpc;
	}

	public CryptoController cryptoGrpc() {
		if (cryptoGrpc == null) {
			cryptoGrpc = new CryptoController(
					metaAnswers(),
					cryptoAnswers(),
					txnResponseHelper(),
					queryResponseHelper());
		}
		return cryptoGrpc;
	}

	public SmartContractServiceImpl contractsGrpc() {
		if (contractsGrpc == null) {
			contractsGrpc = new SmartContractServiceImpl(
					platform,
					txns(),
					contracts(),
					stats(),
					usagePrices(),
					exchange());
		}
		return contractsGrpc;
	}

	public ConsensusController consensusGrpc() {
		if (null == consensusGrpc) {
			consensusGrpc = new ConsensusController(hcsAnswers(), txnResponseHelper(), queryResponseHelper());
		}
		return consensusGrpc;
	}

	public GrpcServerManager grpc() {
		if (grpc == null) {
			grpc = new NettyGrpcServerManager(
					Runtime.getRuntime()::addShutdownHook,
					new NettyServerManager(),
					List.of(filesGrpc(), freezeGrpc(), contractsGrpc(), consensusGrpc(), networkGrpc()),
					List.of(intercept(cryptoGrpc(), new CryptoServiceInterceptor())));
		}
		return grpc;
	}

	public SmartContractRequestHandler contracts() {
		if (contracts == null) {
			contracts = new SmartContractRequestHandler(
					repository(),
					fundingAccount(),
					ledger(),
					accounts(),
					storage(),
					accountSource(),
					txnCtx(),
					exchange(),
					usagePrices(),
					properties(),
					newPureRepo(),
					solidityLifecycle(),
					soliditySigsVerifier());
		}
		return contracts;
	}

	public SolidityLifecycle solidityLifecycle() {
		if (solidityLifecycle == null) {
			solidityLifecycle = new SolidityLifecycle(properties());
		}
		return solidityLifecycle;
	}

	public PropertySource properties() {
		if (properties == null) {
			properties = propertySources().asResolvingSource();
		}
		return properties;
	}

	public SystemFilesManager systemFilesManager() {
		if (systemFilesManager == null)	{
			systemFilesManager = new HfsSystemFilesManager(
					addressBook(),
					fileNums(),
					properties(),
					(TieredHederaFs)hfs(),
					() -> lookupInCustomStore(
							properties.getStringProperty("bootstrap.customKeystore.path"),
							properties.getStringProperty("bootstrap.customKeystore.masterKey")),
					rates -> {
						GlobalFlag.getInstance().setExchangeRateSet(rates);
						if (!midnightRates().isInitialized()) {
							midnightRates().replaceWith(rates);
						}
					},
					config -> {
						((StandardizedPropertySources)propertySources()).updateThrottlePropsFrom(config);
						PropertiesLoader.populateApplicationPropertiesWithProto(config);
					},
					PropertiesLoader::populateAPIPropertiesWithProto);
			/* We must force eager evaluation of the throttle construction here,
			as in DEV environment with the per-classloader singleton pattern used by
			PropertiesLoader, it is otherwise possible to create weird race conditions
			between the initializing threads. */
			var throttles = bucketThrottling();
			PropertiesLoader.registerUpdateCallback(throttles::rebuild);
		}
		return systemFilesManager;
	}

	public ServicesRepositoryRoot repository() {
		if (repository == null) {
			repository = new ServicesRepositoryRoot(accountSource(), bytecodeDb());
			repository.setStoragePersistence(storagePersistence());
		}
		return repository;
	}

	public Supplier<ServicesRepositoryRoot> newPureRepo() {
		if (newPureRepo == null) {
			TransactionalLedger<AccountID, AccountProperty, MerkleAccount> pureDelegate = new TransactionalLedger<>(
					AccountProperty.class,
					MerkleAccount::new,
					new FCMapBackingAccounts(accounts()),
					new ChangeSummaryManager<>());
			HederaLedger pureLedger = new HederaLedger(
					NOOP_ID_SOURCE,
					NOOP_RECORDS_HISTORIAN,
					NOOP_DUPLICATE_CLASSIFIER,
					pureDelegate);
			Source<byte[], AccountState> pureAccountSource = new LedgerAccountsSource(pureLedger, properties());
			newPureRepo = () -> {
				var pureRepository = new ServicesRepositoryRoot(pureAccountSource, bytecodeDb());
				pureRepository.setStoragePersistence(storagePersistence());
				return pureRepository;
			};
		}
		return newPureRepo;
	}

	public ConsensusStatusCounts statusCounts() {
		if (statusCounts == null) {
			statusCounts = new ConsensusStatusCounts(new ObjectMapper());
		}
		return statusCounts;
	}

	public LedgerAccountsSource accountSource() {
		if (accountSource == null) {
			accountSource = new LedgerAccountsSource(ledger(), properties());
		}
		return accountSource;
	}

	public BlobStorageSource bytecodeDb() {
		if (bytecodeDb == null) {
			bytecodeDb = new BlobStorageSource(bytecodeMapFrom(blobStore()));
		}
		return bytecodeDb;
	}

	public TransactionHandler txns() {
		if (txns == null) {
			txns = new TransactionHandler(
					recordCache(),
					precheckVerifier(),
					accounts(),
					nodeAccount(),
					txnThrottling(),
					usagePrices(),
					exchange(),
					fees(),
					stateViews(),
					new BasicPrecheck(validator()),
					queryFeeCheck(),
					bucketThrottling());
		}
		return txns;
	}

	public HederaNodeStats stats() {
		if (stats == null) {
			stats = new HederaNodeStats(platform(), id().getId(), ServicesMain.log);
		}
		return stats;
	}

	public Console console() {
		if (console == null) {
			console = platform().createConsole(true);
		}
		return console;
	}

	public AccountID nodeAccount() {
		if (accountId == null) {
			try {
				String memoOfAccountId = address().getMemo();
				accountId = EntityIdUtils.accountParsedFromString(memoOfAccountId);
			} catch (Exception ignore) {}
		}
		return accountId;
	}

	public AccountID fundingAccount() {
		try {
			return EntityIdUtils.accountParsedFromString(properties().getStringProperty("ledger.funding.account"));
		} catch (Exception ignore) { }
		return null;
	}

	public Address address() {
		if (address == null) {
			address = addressBook().getAddress(id.getId());
		}
		return address;
	}

	public AtomicReference<FCMap<MerkleBlobMeta, MerkleOptionalBlob>>	queryableStorage() {
		if (queryableStorage == null) {
			queryableStorage = new AtomicReference<>(storage());
		}
		return queryableStorage;
	}

	public AtomicReference<FCMap<MerkleEntityId, MerkleAccount>> queryableAccounts() {
		if (queryableAccounts == null) {
			queryableAccounts = new AtomicReference<>(accounts());
		}
		return queryableAccounts;
	}

	public AtomicReference<FCMap<MerkleEntityId, MerkleTopic>> queryableTopics() {
		if (queryableTopics == null) {
			queryableTopics = new AtomicReference<>(topics());
		}
		return queryableTopics;
	}

	public UsagePricesProvider usagePrices() {
		if (usagePrices == null) {
			usagePrices = new AwareFcfsUsagePrices(hfs(), fileNums(), txnCtx());
		}
		return usagePrices;
	}

	public TxnFeeChargingPolicy txnChargingPolicy() {
		if (txnChargingPolicy == null) {
			txnChargingPolicy = new TxnFeeChargingPolicy();
		}
		return txnChargingPolicy;
	}


	public NodeDuplicateClassifier nodeDuplicateClassifier() {
		if (nodeDuplicateClassifier == null)  {
			Supplier<DuplicateClassifier> factory = () ->
					new CountingDuplicateClassifier(properties(), new HashMap<>(), new PriorityBlockingQueue<>());
			nodeDuplicateClassifier = new PerNodeDuplicateClassifier(factory, new HashMap<>());
		}
		return nodeDuplicateClassifier;
	}

	public ScopedDuplicateClassifier duplicateClassifier() {
		if (duplicateClassifier == null) {
			duplicateClassifier = new TxnAwareDuplicateClassifier(txnCtx(), nodeDuplicateClassifier());
		}
		return duplicateClassifier;
	}

	/* Context-free infrastructure. */
	public Pause pause() {
		return pause;
	}

	public StateMigrations stateMigrations() {
		return stateMigrations;
	}

	public AccountsExporter accountsExporter() {
		return accountsExporter;
	}

	public PropertySanitizer propertySanitizer() {
		return propertySanitizer;
	}

	public SystemAccountsCreator systemAccountsCreator() {
		return systemAccountsCreator;
	}

	/* Injected dependencies. */
	public NodeId id() {
		return id;
	}

	public Platform platform() {
		return platform;
	}

	public PropertySources propertySources() {
		return propertySources;
	}

	public Instant consensusTimeOfLastHandledTxn() {
		return state.networkCtx().consensusTimeOfLastHandledTxn();
	}

	public void updateConsensusTimeOfLastHandledTxn(Instant dataDrivenNow) {
		state.networkCtx().setConsensusTimeOfLastHandledTxn(dataDrivenNow);
	}

	public AddressBook addressBook() {
		return state.addressBook();
	}

	public SequenceNumber seqNo() {
		return state.networkCtx().seqNo();
	}

	public ExchangeRates midnightRates() {
		return state.networkCtx().midnightRates();
	}

	public FCMap<MerkleEntityId, MerkleAccount> accounts() {
		return state.accounts();
	}

	public FCMap<MerkleEntityId, MerkleTopic> topics() {
		return state.topics();
	}

	public FCMap<MerkleBlobMeta, MerkleOptionalBlob> storage() {
		return state.storage();
	}
}
