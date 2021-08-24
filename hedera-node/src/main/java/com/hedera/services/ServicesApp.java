package com.hedera.services;

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

import com.hedera.services.context.ContextModule;
import com.hedera.services.context.CurrentPlatformStatus;
import com.hedera.services.context.ServicesNodeType;
import com.hedera.services.context.annotations.BootstrapProps;
import com.hedera.services.context.init.ServicesInitFlow;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.context.properties.PropertiesModule;
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.contracts.ContractsModule;
import com.hedera.services.fees.FeesModule;
import com.hedera.services.files.FilesModule;
import com.hedera.services.grpc.GrpcModule;
import com.hedera.services.grpc.GrpcServerManager;
import com.hedera.services.keys.KeysModule;
import com.hedera.services.ledger.LedgerModule;
import com.hedera.services.ledger.accounts.BackingStore;
import com.hedera.services.queries.QueriesModule;
import com.hedera.services.records.RecordsModule;
import com.hedera.services.sigs.ExpansionHelper;
import com.hedera.services.sigs.SigsModule;
import com.hedera.services.sigs.annotations.RetryingSigReqs;
import com.hedera.services.sigs.order.SigRequirements;
import com.hedera.services.state.DualStateAccessor;
import com.hedera.services.state.StateAccessor;
import com.hedera.services.state.StateModule;
import com.hedera.services.state.annotations.WorkingState;
import com.hedera.services.state.exports.AccountsExporter;
import com.hedera.services.state.exports.BalancesExporter;
import com.hedera.services.state.forensics.HashLogger;
import com.hedera.services.state.initialization.SystemFilesManager;
import com.hedera.services.state.logic.NetworkCtxManager;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.validation.LedgerValidator;
import com.hedera.services.stats.ServicesStatsManager;
import com.hedera.services.stats.StatsModule;
import com.hedera.services.store.StoresModule;
import com.hedera.services.stream.RecordStreamManager;
import com.hedera.services.throttling.ThrottlingModule;
import com.hedera.services.txns.ProcessLogic;
import com.hedera.services.txns.TransactionsModule;
import com.hedera.services.txns.span.ExpandHandleSpan;
import com.hedera.services.txns.submission.SubmissionModule;
import com.hedera.services.utils.NamedDigestFactory;
import com.hedera.services.utils.Pause;
import com.hedera.services.utils.SystemExits;
import com.hederahashgraph.api.proto.java.AccountID;
import com.swirlds.common.Address;
import com.swirlds.common.InvalidSignedStateListener;
import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import dagger.BindsInstance;
import dagger.Component;

import javax.inject.Singleton;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.Optional;
import java.util.function.Supplier;

@Singleton
@Component(modules = {
		FeesModule.class,
		KeysModule.class,
		SigsModule.class,
		GrpcModule.class,
		StatsModule.class,
		StateModule.class,
		FilesModule.class,
		LedgerModule.class,
		StoresModule.class,
		ContextModule.class,
		RecordsModule.class,
		QueriesModule.class,
		ContractsModule.class,
		PropertiesModule.class,
		ThrottlingModule.class,
		SubmissionModule.class,
		TransactionsModule.class,
})
public interface ServicesApp {
	/* Needed by ServicesState */
	HashLogger hashLogger();
	ProcessLogic logic();
	ExpansionHelper expansionHelper();
	ExpandHandleSpan expandHandleSpan();
	ServicesInitFlow initializationFlow();
	DualStateAccessor dualStateAccessor();
	RecordStreamManager recordStreamManager();
	NodeLocalProperties nodeLocalProperties();
	GlobalDynamicProperties globalDynamicProperties();
	@WorkingState StateAccessor workingState();
	@RetryingSigReqs SigRequirements retryingSigReqs();

	/* Needed by ServicesMain */
	Pause pause();
	NodeId nodeId();
	Address nodeAddress();
	SystemExits systemExits();
	LedgerValidator ledgerValidator();
	ServicesNodeType nodeType();
	AccountsExporter accountsExporter();
	BalancesExporter balancesExporter();
	Supplier<Charset> nativeCharset();
	NetworkCtxManager networkCtxManager();
	GrpcServerManager grpc();
	NamedDigestFactory digestFactory();
	SystemFilesManager sysFilesManager();
	ServicesStatsManager statsManager();
	CurrentPlatformStatus platformStatus();
	Optional<PrintStream> consoleOut();
	InvalidSignedStateListener issListener();
	BackingStore<AccountID, MerkleAccount> backingAccounts();

	@Component.Builder
	interface Builder {
		@BindsInstance
		Builder platform(Platform platform);
		@BindsInstance
		Builder selfId(long selfId);
		@BindsInstance
		Builder bootstrapProps(@BootstrapProps PropertySource bootstrapProps);
		@BindsInstance
		Builder initialState(ServicesState initialState);

		ServicesApp build();
	}
}
