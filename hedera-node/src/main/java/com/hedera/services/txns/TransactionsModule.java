package com.hedera.services.txns;

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

import com.hedera.services.fees.annotations.FunctionKey;
import com.hedera.services.legacy.handler.SmartContractRequestHandler;
import com.hedera.services.txns.contract.ConsensusLogicModule;
import com.hedera.services.txns.contract.ContractLogicModule;
import com.hedera.services.txns.contract.ContractSysDelTransitionLogic;
import com.hedera.services.txns.contract.ContractSysUndelTransitionLogic;
import com.hedera.services.txns.crypto.CryptoLogicModule;
import com.hedera.services.txns.customfees.CustomFeeSchedules;
import com.hedera.services.txns.customfees.FcmCustomFeeSchedules;
import com.hedera.services.txns.file.FileLogicModule;
import com.hedera.services.txns.file.FileSysDelTransitionLogic;
import com.hedera.services.txns.file.FileSysUndelTransitionLogic;
import com.hedera.services.txns.network.NetworkLogicModule;
import com.hedera.services.txns.schedule.ScheduleLogicModule;
import com.hedera.services.txns.span.ExpandHandleSpan;
import com.hedera.services.txns.span.SpanMapManager;
import com.hedera.services.txns.token.TokenLogicModule;
import com.hedera.services.txns.validation.ContextOptionValidator;
import com.hedera.services.txns.validation.OptionValidator;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;

import javax.inject.Singleton;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.SystemDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.SystemUndelete;

@Module(includes = {
		FileLogicModule.class,
		TokenLogicModule.class,
		CryptoLogicModule.class,
		NetworkLogicModule.class,
		ScheduleLogicModule.class,
		ContractLogicModule.class,
		ConsensusLogicModule.class
})
public abstract class TransactionsModule {
	@Binds
	@Singleton
	public abstract OptionValidator bindOptionValidator(ContextOptionValidator contextOptionValidator);

	@Binds
	@Singleton
	public abstract CustomFeeSchedules bindCustomFeeSchedules(FcmCustomFeeSchedules fcmCustomFeeSchedules);

	@Provides
	@Singleton
	public static ExpandHandleSpan provideExpandHandleSpan(SpanMapManager spanMapManager) {
		return new ExpandHandleSpan(10, TimeUnit.SECONDS, spanMapManager);
	}

	@Provides
	@Singleton
	public static ContractSysDelTransitionLogic.LegacySystemDeleter provideLegacySystemDeleter(
			SmartContractRequestHandler contracts
	) {
		return contracts::systemDelete;
	}

	@Provides
	@Singleton
	public static ContractSysUndelTransitionLogic.LegacySystemUndeleter provideLegacySystemUndeleter(
			SmartContractRequestHandler contracts
	) {
		return contracts::systemUndelete;
	}

	@Provides
	@IntoMap
	@FunctionKey(SystemDelete)
	public static List<TransitionLogic> provideSystemDeleteLogic(
			FileSysDelTransitionLogic fileSysDelTransitionLogic,
			ContractSysDelTransitionLogic contractSysDelTransitionLogic
	) {
		return List.of(fileSysDelTransitionLogic, contractSysDelTransitionLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(SystemUndelete)
	public static List<TransitionLogic> provideSystemUndeleteLogic(
			FileSysUndelTransitionLogic fileSysUndelTransitionLogic,
			ContractSysUndelTransitionLogic contractSysUndelTransitionLogic
	) {
		return List.of(fileSysUndelTransitionLogic, contractSysUndelTransitionLogic);
	}
}
