package com.hedera.services.legacy.config;

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

import com.hedera.services.context.domain.security.PermissionedAccountsRange;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;
import com.hedera.services.legacy.logic.ApplicationConstants;
import com.hedera.services.legacy.logic.CustomProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class PropertiesLoader {
	public static String applicationPropsFilePath = ApplicationConstants.PROPERTY_FILE;
	public static String apiPropertiesFilePath = ApplicationConstants.API_ACCESS_FILE;
	public static final Logger log = LogManager.getLogger(PropertiesLoader.class);

	public static CustomProperties applicationProps;
	public static CustomProperties apiProperties;
	public static List<Runnable> updateCallbacks = new ArrayList<>();

	public static void registerUpdateCallback(Runnable cb) {
		updateCallbacks.add(cb);
	}

	public static void populateApplicationPropertiesWithProto(ServicesConfigurationList serviceConfigList) {
		Properties properties = new Properties();
		serviceConfigList.getNameValueList().forEach(setting -> {
			properties.setProperty(setting.getName(), setting.getValue());			
		});
		applicationProps = new CustomProperties(properties);		
		SyncPropertiesObject.loadSynchProperties(applicationProps);
		AsyncPropertiesObject.loadAsynchProperties(applicationProps);
		log.info("Application Properties Populated with these values :: "+applicationProps.getCustomProperties());
		updateCallbacks.forEach(Runnable::run);
	}
	
	public static void populateAPIPropertiesWithProto(ServicesConfigurationList serviceConfigList) {
		Properties properties = new Properties();
		serviceConfigList.getNameValueList().forEach(setting -> {
			properties.setProperty(setting.getName(), setting.getValue());			
		});
		apiProperties = new CustomProperties(properties);		
		AsyncPropertiesObject.loadApiProperties(apiProperties);		
		log.info("API Properties Populated with these values :: "+apiProperties.getCustomProperties());
	}

	public static double getCreateTopicTps() {
		return AsyncPropertiesObject.getCreateTopicTps();
	}

	public static double getCreateTopicBurstPeriod() {
		return AsyncPropertiesObject.getCreateTopicBurstPeriod();
	}

	public static double getUpdateTopicTps() {
		return AsyncPropertiesObject.getUpdateTopicTps();
	}

	public static double getUpdateTopicBurstPeriod() {
		return AsyncPropertiesObject.getUpdateTopicBurstPeriod();
	}

	public static double getDeleteTopicTps() {
		return AsyncPropertiesObject.getDeleteTopicTps();
	}

	public static double getDeleteTopicBurstPeriod() {
		return AsyncPropertiesObject.getDeleteTopicBurstPeriod();
	}

	public static double getSubmitMessageTps() {
		return AsyncPropertiesObject.getSubmitMessageTps();
	}

	public static double getSubmitMessageBurstPeriod() {
		return AsyncPropertiesObject.getSubmitMessageBurstPeriod();
	}

	public static double getGetTopicInfoTps() {
		return AsyncPropertiesObject.getGetTopicInfoTps();
	}

	public static double getGetTopicInfoBurstPeriod() {
		return AsyncPropertiesObject.getGetTopicInfoBurstPeriod();
	}

	public static int getTransferAccountListSize() {
		return SyncPropertiesObject.getTransferListSizeLimit();
	}

	public static long getExpiryTime() {
		return SyncPropertiesObject.getExpiryTime();
	}

	public static String getHederaStartupPath() {
		return AsyncPropertiesObject.getHederaStartupPath();
	}

	public static String getGenAccountPath() {
		return AsyncPropertiesObject.getGenesisAccountPath();
	}

	public static String getGenPub32KeyPath() {
		return AsyncPropertiesObject.getGenesisPubKey32BytePath();
	}

	public static String getGenPubKeyPath() {
		return AsyncPropertiesObject.getGEN_PUB_KEY_PATH();
	}

	public static String getGenPrivKeyPath() {
		return AsyncPropertiesObject.getGenesisPrivKeyPath();
	}

	public static long getInitialGenesisCoins() {
		return SyncPropertiesObject.getInitialGenesisCoins();
	}

	public static long getInitialCoins() {
		return SyncPropertiesObject.getInitialCoins();
	}

	public static long getDefaultContractDurationInSec() {
		return SyncPropertiesObject.getDefaultContractDurationSec();
	}

	public static int getKeyExpansionDepth() {
		return SyncPropertiesObject.getKeyExpansionDepth();
	}

	public static int getTxReceiptTTL() {
		return SyncPropertiesObject.getTxReceiptTTL();
	}

	public static int getThresholdTxRecordTTL() {
		return SyncPropertiesObject.getThresholdTxRecordTTL();
	}

	public static int getThrottlingTps() {
		return AsyncPropertiesObject.getThrottlingTps();
	}

	public static int getSimpleTransferTps() {
		return AsyncPropertiesObject.getSimpletransferTps();
	}

	public static int getGetReceiptTps() {
		return AsyncPropertiesObject.getGetReceiptTps();
	}

	public static int getQueriesTps() {
		return AsyncPropertiesObject.getQueriesTps();
	}

	public static boolean getFileExistenceCheck(String propertyFile) {
		File f = new File(propertyFile);
		return f.exists();
	}

	public static String getAddressBook() {
		return AsyncPropertiesObject.getAddressBook();
	}

	public static String getInitializeHederaFlag() {
		return AsyncPropertiesObject.getInitializeHederaLedgerFlag();
	}

	public static String getFeeCollectionAccount() {
		return SyncPropertiesObject.getDefaultFeeCollectionAccount();
	}

	public static long getMinimumAutorenewDuration() {
		return SyncPropertiesObject.getMINIMUM_AUTORENEW_DURATION();
	}

	public static long getMaximumAutorenewDuration() {
		return SyncPropertiesObject.getMAXIMUM_AUTORENEW_DURATION();
	}

	public static long getRecordLogPeriod() {
		return AsyncPropertiesObject.getRecordLogPeriod();
	}

	public static String getRecordLogDir() {
		return AsyncPropertiesObject.getRecordLogDir();
	}

	public static boolean isAccountBalanceExportEnabled() {
		return AsyncPropertiesObject.isAccountBalanceExportEnabled();
	}

	public static String getAccountBalanceExportDir() {
		return AsyncPropertiesObject.getAccountBalanceExportDir();
	}

	public static long accountBalanceExportPeriodMinutes() {
		return AsyncPropertiesObject.accountBalanceExportPeriodMinutes();
	}

	public static long getGenesisAccountNum() {
		return SyncPropertiesObject.getGenesisAccountNum();
	}

	public static long getMasterAccountNum() {
		return SyncPropertiesObject.getMasterAccountNum();
	}

	public static long getProtectedMaxEntityNum() {
		return SyncPropertiesObject.getProtectedMaxEntityNum();
	}

	public static long getProtectedMinEntityNum() {
		return SyncPropertiesObject.getProtectedMinEntityNum();
	}

	public static long getDefaultContractSenderThreshold() {
		return SyncPropertiesObject.getDefaultContractSenderThreshold();
	}

	public static long getDefaultContractReceiverThreshold() {
		return SyncPropertiesObject.getDefaultContractReceiverThreshold();
	}

	public static long getNodeAccountBalanceValidity() {
		return SyncPropertiesObject.getNodeAccountBalanceValidity();
	}

	public static int getCurrentHbarEquivalent() {
		return SyncPropertiesObject.getCurrentHbarEquivalent();
	}

	public static int getCurrentCentEquivalent() {
		return SyncPropertiesObject.getCurrentCentEquivalent();
	}

	public static int getRecordStreamQueueCapacity() {
		return AsyncPropertiesObject.getRecordStreamQueueCapacity();
	}

	public static long getConfigAccountNum() {
		return SyncPropertiesObject.getConfigAccountNum();
	}

	public static long getConfigRealmNum() {
		return SyncPropertiesObject.getConfigRealmNum();
	}

	public static long getConfigShardNum() {
		return SyncPropertiesObject.getConfigShardNum();
	}

	public static int getlocalCallEstReturnBytes() {
		return SyncPropertiesObject.getLocalCallEstReturnBytes();
	}

	public static int getExchangeRateAllowedPercentage() {
		return SyncPropertiesObject.getExchangeRateAllowedPercentage();
	}

	public static int getTxMinRemaining() {
		return SyncPropertiesObject.getTxMinRemaining();
	}

	public static int getTxMinDuration() {
		return SyncPropertiesObject.getTxMinDuration();
	}

	public static int getTxMaxDuration() {
		return SyncPropertiesObject.getTxMaxDuration();
	}

	public static int getMaxContractStateSize() {
		return SyncPropertiesObject.getMaxContractStateSize();
	}

	/**
	 * If Exchange_Rate_Allowed_Percentage in application.properties is invalid,
	 * i.e. <=0, we set it to be DEFAULT_EXCHANGE_RATE_ALLOWED_PERCENTAGE, and
	 * return false; else return true;
	 * 
	 * @return
	 */
	public static boolean validExchangeRateAllowedPercentage() {
		return SyncPropertiesObject.validExchangeRateAllowedPercentage();
	}

	public static boolean isEnableRecordStreaming() {
		return AsyncPropertiesObject.isEnableRecordStreaming();
	}

	public static Map<String, PermissionedAccountsRange> getApiPermission() {
		return AsyncPropertiesObject.getApiPermission();
	}

	public static int getPort() {
		return AsyncPropertiesObject.getPort();
	}

	public static int getTlsPort() {
		return AsyncPropertiesObject.getTlsPort();
	}

	public static int getEnvironment() {
		return AsyncPropertiesObject.getEnvironment();
	}

	public static String getDefaultListeningNodeAccount() {
		return AsyncPropertiesObject.getDefaultListeningNodeAccount();
	}

	public static int getUniqueListeningPortFlag() {
		return AsyncPropertiesObject.getUniqueListeningPortFlag();
	}

	public static String getSaveAccounts() {
		return AsyncPropertiesObject.getSaveAccounts();
	}

	public static String getExportedAccountPath() {
		return AsyncPropertiesObject.getExportedAccountPath();
	}

	public static long getNettyKeepAliveTime() {
		return AsyncPropertiesObject.getNettyKeepAliveTime();
	}

	public static long getNettyKeepAliveTimeOut() {
		return AsyncPropertiesObject.getNettyKeepAliveTimeOut();
	}

	public static long getNettyMaxConnectionAge() {
		return AsyncPropertiesObject.getNettyMaxConnectionAge();
	}

	public static long getNettyMaxConnectionAgeGrace() {
		return AsyncPropertiesObject.getNettyMaxConnectionAgeGrace();
	}

	public static long getNettyMaxConnectionIdle() {
		return AsyncPropertiesObject.getNettyMaxConnectionIdle();
	}

	public static int getNettyMaxConcurrentCalls() {
		return AsyncPropertiesObject.getNettyMaxConcurrentCalls();
	}

	public static int getNettyFlowControlWindow() {
		return AsyncPropertiesObject.getNettyFlowControlWindow();
	}

	public static String getNettyMode() {
		return AsyncPropertiesObject.getNettyMode();
	}
	
	 public static int getMaxGasLimit() {
		 return SyncPropertiesObject.getMaxGasLimit();
	}

    public static String getSkipExitOnStartupFailures() {
      return AsyncPropertiesObject.getSkipExitOnStartupFailures();
    }
    
    public static int getMaxFileSize() {
		 return SyncPropertiesObject.getMaxFileSize();
	}

	public static boolean getStartStatsDumpTimer() {
		return AsyncPropertiesObject.getStartStatsDumpTimer();
	}

	public static int getStatsDumpTimerValue() {
		return AsyncPropertiesObject.getStatsDumpTimerValue();
	}

}
