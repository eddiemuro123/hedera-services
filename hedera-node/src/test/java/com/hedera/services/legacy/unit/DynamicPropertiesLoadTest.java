package com.hedera.services.legacy.unit;

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


import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.hedera.services.fees.calculation.FeeCalcUtils;
import com.hedera.services.legacy.config.PropertiesLoader;
import com.hedera.services.legacy.core.jproto.JFileInfo;
import com.hedera.services.legacy.handler.FCStorageWrapper;
import com.hedera.services.legacy.handler.TransactionHandler;
import com.hedera.services.legacy.logic.ApplicationConstants;
import com.hedera.services.legacy.unit.handler.FeeScheduleInterceptor;
import com.hedera.services.legacy.unit.handler.FileServiceHandler;
import com.swirlds.fcmap.FCMap;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FileCreateTransactionBody;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.FileUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;
import com.hederahashgraph.api.proto.java.Setting;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.builder.RequestBuilder;
import com.hedera.services.legacy.TestHelper;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleBlobMeta;
import com.hedera.services.state.merkle.MerkleOptionalBlob;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.legacy.exception.InvalidFileWACLException;
import com.hedera.services.legacy.exception.SerializationException;
import com.hedera.services.state.submerkle.ExchangeRates;

import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;

public class DynamicPropertiesLoadTest {
	long payerAccount;
	long nodeAccount;
	private AccountID nodeAccountId;
	private AccountID payerAccountId;
	FCStorageWrapper storageWrapper;
	TransactionHandler transactionHandler = null;
	FCMap<MerkleEntityId, MerkleAccount> fcMap = null;
	private FCMap<MerkleBlobMeta, MerkleOptionalBlob> storageMap;
	private FileServiceHandler fileServiceHandler;
	FileCreateTransactionBody fileCreateTransactionBody;

	@Before
	public void setUp() throws Exception {
		payerAccount = 2l;
		nodeAccount = 3l;
		payerAccountId = RequestBuilder.getAccountIdBuild(payerAccount, 0l, 0l);
		nodeAccountId = RequestBuilder.getAccountIdBuild(nodeAccount, 0l, 0l);
		fcMap = new FCMap<>(new MerkleEntityId.Provider(), MerkleAccount.LEGACY_PROVIDER);
		storageMap = new FCMap<>(new MerkleBlobMeta.Provider(), new MerkleOptionalBlob.Provider());
		storageWrapper = new FCStorageWrapper(storageMap);
		FeeScheduleInterceptor feeScheduleInterceptor = mock(FeeScheduleInterceptor.class);
		fileServiceHandler = new FileServiceHandler(
				storageWrapper,
				feeScheduleInterceptor,
				new ExchangeRates());
	}
		
	@Test
	public void testInitialiseAndChangeProperties() throws SerializationException, InvalidFileWACLException{
		// setup:
		ServicesConfigurationList serviceConfigList = getAppPropertiesProto("180", "90000");
		FileID fileId = FileID.newBuilder().setFileNum(121).setRealmNum(0).setShardNum(0).build();
		createFile(fileId,serviceConfigList.toByteArray());

		// given:
		serviceConfigList = getAppPropertiesProto("180", "90000");
		TransactionBody txBody = getTxBody(serviceConfigList.toByteArray());
		fileServiceHandler.updateFile(txBody, Instant.now());
		Assert.assertEquals(180, PropertiesLoader.getTxReceiptTTL());
		assertEquals(90000, PropertiesLoader.getThresholdTxRecordTTL());
		
		// now update the file with changed Proto
		serviceConfigList = getAppPropertiesProto("280", "80000");
		txBody = getTxBody(serviceConfigList.toByteArray());
		fileServiceHandler.updateFile(txBody, Instant.now());
		assertEquals(280, PropertiesLoader.getTxReceiptTTL());
		assertEquals(80000, PropertiesLoader.getThresholdTxRecordTTL());
		
		// change back the value of receipt time to 180
		serviceConfigList = getAppPropertiesProto("180", "90000");
		txBody = getTxBody(serviceConfigList.toByteArray());
		fileServiceHandler.updateFile(txBody, Instant.now());
		assertEquals(180, PropertiesLoader.getTxReceiptTTL());
		assertEquals(90000, PropertiesLoader.getThresholdTxRecordTTL());
	}
	
	private TransactionBody getTxBody(byte [] fileData) {
		Timestamp timestamp = TestHelper.getDefaultCurrentTimestampUTC();
	    Timestamp fileExp = TestHelper.getDefaultCurrentTimestampUTC();
	    Duration transactionDuration = RequestBuilder.getDuration(180);
	    FileID fileId = FileID.newBuilder().setFileNum(121).setRealmNum(0).setShardNum(0).build();
	    FileUpdateTransactionBody fileUpdateTransactionBody = FileUpdateTransactionBody.newBuilder()
		        .setExpirationTime(fileExp)
		        .setFileID(fileId)
		        .setKeys(KeyList.newBuilder().addAllKeys(genWacl()).build())
		        .setContents(ByteString.copyFrom(fileData)).build();
		
		TransactionBody.Builder body = getTransactionBody(payerAccountId,
				nodeAccountId, 100l, timestamp, transactionDuration,
		        false, "Properties File Update Test");
		return body.setFileUpdate(fileUpdateTransactionBody).build();
		
	}
	
		
	  private static TransactionBody.Builder getTransactionBody(AccountID payerAccountID, AccountID nodeAccountID, 
			  long transactionFee, Timestamp timestamp, Duration transactionDuration,
		      boolean generateRecord, String memo) {		   
		    TransactionID transactionID = TransactionID.newBuilder().setAccountID(payerAccountID)
		            .setTransactionValidStart(timestamp).build();
		    return TransactionBody.newBuilder().setTransactionID(transactionID)
		        .setNodeAccountID(nodeAccountID)
		        .setTransactionFee(transactionFee).setTransactionValidDuration(transactionDuration)
		        .setGenerateRecord(generateRecord).setMemo(memo);
		 }
	  
	  
	  public List<Key> genWacl() {
		  List<Key> waclPubKeyList = new ArrayList<>();		  
	      KeyPair pair = new KeyPairGenerator().generateKeyPair();
	      byte[] pubKey = ((EdDSAPublicKey) pair.getPublic()).getAbyte();
	      Key waclKey = Key.newBuilder().setEd25519(ByteString.copyFrom(pubKey)).build();
	      waclPubKeyList.add(waclKey);
		  return waclPubKeyList;
	 }	  
	  
	  public ServicesConfigurationList getAppPropertiesProto(String txReceiptTTL, String thresholdTxRecordTTL) {
		  ServicesConfigurationList.Builder serviceConfigListBuilder = ServicesConfigurationList.newBuilder();
		  Setting setting = Setting.newBuilder().setName(String.valueOf("txReceiptTTL")).setValue(txReceiptTTL).build();
		  serviceConfigListBuilder.addNameValue(setting);
		  setting = Setting.newBuilder().setName(String.valueOf("thresholdTxRecordTTL")).setValue(thresholdTxRecordTTL).build();
		  serviceConfigListBuilder.addNameValue(setting);
		  return serviceConfigListBuilder.build();
	  }
	  
	  private void createFile(FileID fid, byte[] fileData)
		      throws SerializationException, InvalidFileWACLException {
		    long startTime = ApplicationConstants.CURRENT_TIME;
		    long expiryTime = ApplicationConstants.EXPIRY_TIME;
		    // get the System Startup Account
		    List<Key> keyList = genWacl();
		    Key key = keyList.get(0);
		    JKey jkey = JFileInfo.convertWacl(KeyList.newBuilder().addKeys(key).build());
		    String fileDataPath = FeeCalcUtils.pathOf(fid);
		    storageWrapper.fileCreate(fileDataPath, fileData, startTime, 0, expiryTime, null);
		    JFileInfo jFileInfo = new JFileInfo(false, jkey, expiryTime);
		    byte[] bytes = jFileInfo.serialize();
		    String fileMetaDataPath = FeeCalcUtils.pathOfMeta(fid);
		    storageWrapper.fileCreate(fileMetaDataPath, bytes, startTime, 0, expiryTime, null);
		  }
	  
	  

}
