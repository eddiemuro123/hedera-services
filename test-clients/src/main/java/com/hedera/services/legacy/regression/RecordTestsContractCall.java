package com.hedera.services.legacy.regression;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionGetReceiptResponse;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import com.hederahashgraph.builder.RequestBuilder;
import com.hederahashgraph.service.proto.java.CryptoServiceGrpc;
import com.hederahashgraph.service.proto.java.SmartContractServiceGrpc;
import com.hedera.services.legacy.core.AccountKeyListObj;
import com.hedera.services.legacy.core.CommonUtils;
import com.hedera.services.legacy.core.CustomPropertiesSingleton;
import com.hedera.services.legacy.core.FeeClient;
import com.hedera.services.legacy.core.KeyPairObj;
import com.hedera.services.legacy.core.TestHelper;
import com.hedera.services.legacy.file.LargeFileUploadIT;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.ethereum.core.CallTransaction;
import org.junit.Assert;

/**
 * Check records for smart contract call failures. 
 * 
 * @author Hua Li
 * Created on 2019-06-10
 * Based on SmartContractInvalidCallWithValue.java
 */
public class RecordTestsContractCall {
  public static final long INITIAL_PAYER_BALANCE = (TestHelper.getContractMaxFee()*5);
  public static long GAS_COST = 1L;
  private static long DAY_SEC = 24 * 60 * 60; // secs in a day
  private final static long CONTRACT_CREATE_SUCCESS_GAS = 250000L;
  private final Logger log = LogManager.getLogger(RecordTestsContractCall.class);

  private static final int MAX_RECEIPT_RETRIES = 60;
  private static final String SC_GET_ABI = "{\"constant\":true,\"inputs\":[],\"name\":\"get\",\"outputs\":[{\"name\":\"\",\"type\":\"uint256\"}],\"payable\":false,\"stateMutability\":\"view\",\"type\":\"function\"}";
  private static final String SC_SET_ABI = "{\"constant\":false,\"inputs\":[{\"name\":\"x\",\"type\":\"uint256\"}],\"name\":\"set\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"}";
  private static AccountID nodeAccount;
  private static long node_account_number;
  private static long node_shard_number;
  private static long node_realm_number;
  public static String INITIAL_ACCOUNTS_FILE = TestHelper.getStartUpFile();
  private AccountID genesisAccount;
  private KeyPair genesisKeyPair;
  private Map<AccountID, KeyPair> accountKeyPairs = new HashMap<>();
  private static String host;
  private static int port;
  private Transaction lastTx; 

  public static void main(String args[]) throws Exception {
    Properties properties = TestHelper.getApplicationProperties();
    host = properties.getProperty("host");
    port = Integer.parseInt(properties.getProperty("port"));
    node_account_number = Utilities.getDefaultNodeAccount();
    node_shard_number = Long.parseLong(properties.getProperty("NODE_REALM_NUMBER"));
    node_realm_number = Long.parseLong(properties.getProperty("NODE_SHARD_NUMBER"));
    nodeAccount = AccountID.newBuilder().setAccountNum(node_account_number)
        .setRealmNum(node_shard_number).setShardNum(node_realm_number).build();

    int numberOfReps = 1;
    if ((args.length) > 0) {
      numberOfReps = Integer.parseInt(args[0]);
    }
    for (int i = 0; i < numberOfReps; i++) {
      RecordTestsContractCall scFf = new RecordTestsContractCall();
      scFf.demo();
    }

  }

  private void loadGenesisAndNodeAcccounts() throws Exception {
    Map<String, List<AccountKeyListObj>> hederaAccounts = null;
    Map<String, List<AccountKeyListObj>> keyFromFile = TestHelper.getKeyFromFile(INITIAL_ACCOUNTS_FILE);

    // Get Genesis Account key Pair
    List<AccountKeyListObj> genesisAccountList = keyFromFile.get("START_ACCOUNT");
    ;

    // get Private Key
    KeyPairObj genKeyPairObj = genesisAccountList.get(0).getKeyPairList().get(0);
    PrivateKey genesisPrivateKey = genKeyPairObj.getPrivateKey();
    genesisKeyPair = new KeyPair(genKeyPairObj.getPublicKey(), genesisPrivateKey);

    // get the Account Object
    genesisAccount = genesisAccountList.get(0).getAccountId();
    accountKeyPairs.put(genesisAccount, genesisKeyPair);
  }

  private Transaction createQueryHeaderTransfer(AccountID payer, long transferAmt)
      throws Exception {
    Transaction transferTx = TestHelper.createTransferSigMap(payer, accountKeyPairs.get(payer),
        nodeAccount, payer, accountKeyPairs.get(payer), nodeAccount, transferAmt);
    return transferTx;

  }

  private AccountID createAccount(KeyPair keyPair, AccountID payerAccount, long initialBalance)
      throws Exception {
    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();
    CryptoServiceGrpc.CryptoServiceBlockingStub stub = CryptoServiceGrpc.newBlockingStub(channel);
    Transaction transaction = TestHelper
        .createAccountWithSigMap(payerAccount, nodeAccount, keyPair, initialBalance,
            accountKeyPairs.get(payerAccount));
    TransactionResponse response = stub.createAccount(transaction);
    Assert.assertNotNull(response);
    Assert.assertEquals(ResponseCodeEnum.OK, response.getNodeTransactionPrecheckCode());
    System.out.println(
        "Pre Check Response of Create  account :: " + response.getNodeTransactionPrecheckCode()
            .name());

    TransactionBody body = TransactionBody.parseFrom(transaction.getBodyBytes());
    AccountID newlyCreateAccountId = TestHelper
        .getTxReceipt(body.getTransactionID(), stub).getAccountID();
    accountKeyPairs.put(newlyCreateAccountId, keyPair);
    channel.shutdown();
    return newlyCreateAccountId;
  }

  private TransactionGetReceiptResponse getReceipt(TransactionID transactionId) throws Exception {
    TransactionGetReceiptResponse receiptToReturn = null;
    Query query = Query.newBuilder()
        .setTransactionGetReceipt(RequestBuilder.getTransactionGetReceiptQuery(
            transactionId, ResponseType.ANSWER_ONLY)).build();
    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();
    CryptoServiceGrpc.CryptoServiceBlockingStub stub = CryptoServiceGrpc.newBlockingStub(channel);
    Response transactionReceipts = stub.getTransactionReceipts(query);
    int attempts = 1;
    while (attempts <= MAX_RECEIPT_RETRIES && transactionReceipts.getTransactionGetReceipt()
        .getReceipt()
        .getStatus().name().equalsIgnoreCase(ResponseCodeEnum.UNKNOWN.name())) {
      Thread.sleep(1000);
      transactionReceipts = stub.getTransactionReceipts(query);
      System.out.println("waiting to getTransactionReceipts as not Unknown..." +
          transactionReceipts.getTransactionGetReceipt().getReceipt().getStatus().name());
      attempts++;
    }
    if (transactionReceipts.getTransactionGetReceipt().getReceipt().getStatus()
        .equals(ResponseCodeEnum.SUCCESS)) {
      receiptToReturn = transactionReceipts.getTransactionGetReceipt();
    }
    channel.shutdown();
    return transactionReceipts.getTransactionGetReceipt();

  }

  private ContractID createContractWithGasAndBalance(AccountID payerAccount, FileID contractFile,
      long durationInSeconds, long gas, long balance, ResponseCodeEnum expectedStatus)
      throws Exception {
    ContractID createdContract = null;
    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();

    Duration contractAutoRenew = Duration.newBuilder().setSeconds(durationInSeconds).build();
    SmartContractServiceGrpc.SmartContractServiceBlockingStub stub = SmartContractServiceGrpc
        .newBlockingStub(channel);

    Timestamp timestamp = RequestBuilder
        .getTimestamp(Instant.now(Clock.systemUTC()).minusSeconds(13));
    Duration transactionDuration = RequestBuilder.getDuration(30);
    Transaction createContractRequest = TestHelper
        .getCreateContractRequestSigMap(payerAccount.getAccountNum(), payerAccount.getRealmNum(),
            payerAccount.getShardNum(), nodeAccount.getAccountNum(), nodeAccount.getRealmNum(),
            nodeAccount.getShardNum(), 100l, timestamp,
            transactionDuration, true, "", gas, contractFile, ByteString.EMPTY, balance,
            contractAutoRenew, accountKeyPairs.get(payerAccount), "", null);

    TransactionResponse response = stub.createContract(createContractRequest);
    System.out.println(
        " createContractWithGasAndBalance Pre Check Response :: " + response.getNodeTransactionPrecheckCode()
            .name());
    lastTx = createContractRequest;
    
    TransactionBody createContractBody = TransactionBody.parseFrom(createContractRequest.getBodyBytes());
    TransactionGetReceiptResponse contractCreateReceipt = getReceipt(
    		createContractBody.getTransactionID());
    if (contractCreateReceipt != null) {
      createdContract = contractCreateReceipt.getReceipt().getContractID();
    }
    ResponseCodeEnum statusCode = contractCreateReceipt.getReceipt().getStatus();
    Assert.assertTrue("Unexpected status code " + statusCode, expectedStatus.equals(statusCode));
    channel.shutdown();

    return createdContract;
  }

  private byte[] encodeSet(int valueToAdd) {
    String retVal = "";
    CallTransaction.Function function = getSetFunction();
    byte[] encodedFunc = function.encode(valueToAdd);

    return encodedFunc;
  }

  private CallTransaction.Function getSetFunction() {
    String funcJson = SC_SET_ABI.replaceAll("'", "\"");
    CallTransaction.Function function = CallTransaction.Function.fromJsonInterface(funcJson);
    return function;
  }

  private long invalidCallContract(AccountID payerAccount, ContractID contractToCall, byte[] data)
      throws Exception {
    long totalCost = 0; // gas plus fees
    ContractID createdContract = null;
    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();
    SmartContractServiceGrpc.SmartContractServiceBlockingStub stub = SmartContractServiceGrpc
        .newBlockingStub(channel);

    Timestamp timestamp = RequestBuilder
        .getTimestamp(Instant.now(Clock.systemUTC()).minusSeconds(13));
    Duration transactionDuration = RequestBuilder.getDuration(30);
    ByteString dataBstr = ByteString.EMPTY;
    if (data != null) {
      dataBstr = ByteString.copyFrom(data);
    }
    // Passing a value to this method is not valid.
    Transaction callContractRequest = TestHelper
        .getContractCallRequestSigMap(payerAccount.getAccountNum(), payerAccount.getRealmNum(),
            payerAccount.getShardNum(), node_account_number, 0l, 0l, 100l, timestamp,
            transactionDuration, 250000, contractToCall, dataBstr, 90_000_000L,
            accountKeyPairs.get(payerAccount));

    TransactionResponse response = stub.contractCallMethod(callContractRequest);
    System.out.println(
        " invalidCallContract Pre Check Response :: " + response.getNodeTransactionPrecheckCode()
            .name());
    lastTx = callContractRequest;
    
    Thread.sleep(1000);
    TransactionBody callContractBody = TransactionBody.parseFrom(callContractRequest.getBodyBytes());
    TransactionGetReceiptResponse contractCallReceipt = getReceipt(
        callContractBody.getTransactionID());

    if (contractCallReceipt != null) {

      Assert.assertEquals(contractCallReceipt.getReceipt().getStatus(), ResponseCodeEnum.CONTRACT_REVERT_EXECUTED);
      TransactionRecord trRecord = getTransactionRecord(payerAccount,
          callContractBody.getTransactionID());
      if (trRecord != null && trRecord.hasContractCallResult()) {
        ContractFunctionResult callResults = trRecord.getContractCallResult();
        String errMsg = callResults.getErrorMessage();
        totalCost = trRecord.getTransactionFee() + (GAS_COST * callResults.getGasUsed());
        if (!StringUtils.isEmpty(errMsg)) {
          log.info("@@@ Contract Call resulted in error: " + errMsg);
        }
      }
    }
    channel.shutdown();

    return totalCost;
  }

  private TransactionRecord getTransactionRecord(AccountID payerAccount,
      TransactionID transactionId) throws Exception {
    AccountID createdAccount = null;
    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();
    CryptoServiceGrpc.CryptoServiceBlockingStub stub = CryptoServiceGrpc.newBlockingStub(channel);
    long fee = FeeClient.getCostForGettingTxRecord();
    Response recordResp = executeQueryForTxRecord(payerAccount, transactionId, stub, fee,
        ResponseType.COST_ANSWER);
    fee = recordResp.getTransactionGetRecord().getHeader().getCost();
    recordResp = executeQueryForTxRecord(payerAccount, transactionId, stub, fee,
        ResponseType.ANSWER_ONLY);
    TransactionRecord txRecord = recordResp.getTransactionGetRecord().getTransactionRecord();
    log.info("tx record = " + txRecord);
    channel.shutdown();
    return txRecord;
  }

  private Response executeQueryForTxRecord(AccountID payerAccount, TransactionID transactionId,
      CryptoServiceGrpc.CryptoServiceBlockingStub stub, long fee, ResponseType responseType)
      throws Exception {
    // Use genesis account so only what's in the transaction record is charged to our payer.
    Transaction paymentTx = createQueryHeaderTransfer(genesisAccount, fee);
    Query getRecordQuery = RequestBuilder
        .getTransactionGetRecordQuery(transactionId, paymentTx, responseType);
    Response recordResp = stub.getTxRecordByTxID(getRecordQuery);
    return recordResp;
  }

  private CallTransaction.Function getGetValueFunction() {
    String funcJson = SC_GET_ABI.replaceAll("'", "\"");
    CallTransaction.Function function = CallTransaction.Function.fromJsonInterface(funcJson);
    return function;
  }

  private byte[] encodeGetValue() {
    String retVal = "";
    CallTransaction.Function function = getGetValueFunction();
    byte[] encodedFunc = function.encode();
    return encodedFunc;
  }

  private int decodeGetValueResult(byte[] value) {
    int decodedReturnedValue = 0;
    CallTransaction.Function function = getGetValueFunction();
    Object[] retResults = function.decodeResult(value);
    if (retResults != null && retResults.length > 0) {
      BigInteger retBi = (BigInteger) retResults[0];
      decodedReturnedValue = retBi.intValue();
    }
    return decodedReturnedValue;
  }

  private byte[] callContractLocal(AccountID payerAccount, ContractID contractToCall, byte[] data)
      throws Exception {
    byte[] dataToReturn = null;
    AccountID createdAccount = null;
    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();
    SmartContractServiceGrpc.SmartContractServiceBlockingStub stub = SmartContractServiceGrpc
        .newBlockingStub(channel);
    ByteString callData = ByteString.EMPTY;
    int callDataSize = 0;
    if (data != null) {
      callData = ByteString.copyFrom(data);
      callDataSize = callData.size();
    }
    long fee = FeeClient.getCostContractCallLocalFee(callDataSize);
    Response callResp = executeContractCall(payerAccount, contractToCall, stub, callData, fee,
        ResponseType.COST_ANSWER);
    fee = callResp.getContractCallLocal().getHeader().getCost();
    callResp = executeContractCall(payerAccount, contractToCall, stub, callData, fee,
        ResponseType.ANSWER_ONLY);
    System.out.println("callContractLocal response = " + callResp);
    ByteString functionResults = callResp.getContractCallLocal().getFunctionResult()
        .getContractCallResult();

    channel.shutdown();
    return functionResults.toByteArray();
  }

  private Response executeContractCall(AccountID payerAccount, ContractID contractToCall,
      SmartContractServiceGrpc.SmartContractServiceBlockingStub stub, ByteString callData, long fee,
      ResponseType resposeType)
      throws Exception {
    Transaction paymentTx = createQueryHeaderTransfer(payerAccount, fee);
    Query contractCallLocal = RequestBuilder
        .getContractCallLocalQuery(contractToCall, 250000L, callData, 0L, 5000, paymentTx,
            resposeType);

    Response callResp = stub.contractCallLocalMethod(contractCallLocal);
    return callResp;
  }

  private int getValueFromContract(AccountID payerAccount, ContractID contractId) throws Exception {
    int retVal = 0;
    byte[] getValueEncodedFunction = encodeGetValue();
    byte[] result = callContractLocal(payerAccount, contractId, getValueEncodedFunction);
    if (result != null && result.length > 0) {
      retVal = decodeGetValueResult(result);
    }
    return retVal;
  }

  private long invalidSetValueToContract(AccountID payerAccount, ContractID contractId, int valuetoSet)
      throws Exception {
    byte[] dataToSet = encodeSet(valuetoSet);
    //set value to simple storage smart contract
    long totalCost = invalidCallContract(payerAccount, contractId, dataToSet);
    return totalCost;
  }

  private Response executeGetAcctInfoQuery(AccountID accountID,
      CryptoServiceGrpc.CryptoServiceBlockingStub stub,
      long fee, ResponseType responseType) throws Exception {
    Transaction paymentTx = createQueryHeaderTransfer(accountID, fee);
    Query cryptoGetInfoQuery = RequestBuilder
        .getCryptoGetInfoQuery(accountID, paymentTx, responseType);

    Response respToReturn = stub.getAccountInfo(cryptoGetInfoQuery);
    return respToReturn;
  }

  public void demo() throws Exception {
    loadGenesisAndNodeAcccounts();

    KeyPair crAccountKeyPair = new KeyPairGenerator().generateKeyPair();
    AccountID crAccount = createAccount(crAccountKeyPair, genesisAccount, INITIAL_PAYER_BALANCE);
    log.info("Account created successfully");
    String fileName = "simpleStorage.bin";
    if (crAccount != null) {

      // Get stub for querying cr account balance
      ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
          .usePlaintext(true)
          .build();
      CryptoServiceGrpc.CryptoServiceBlockingStub balanceStub = CryptoServiceGrpc.newBlockingStub(channel);

      TestHelper.initializeFeeClient(channel, genesisAccount, accountKeyPairs.get(genesisAccount),
          nodeAccount);
      FeeUtility fUtil = FeeUtility.getInstance();
      GAS_COST = fUtil.getCallPriceInTinybars();


      long expectedCost; // Cost from transaction record
      long creatingAccountBalance, beforeBalance;
      creatingAccountBalance = Utilities.getAccountBalance(balanceStub, crAccount,
          genesisAccount, genesisKeyPair, nodeAccount, TestHelper.getCryptoMaxFee());
      beforeBalance = creatingAccountBalance;
      log.info("Creating account balance is initially " + creatingAccountBalance);

      FileID simpleStorageFileId = LargeFileUploadIT
          .uploadFile(crAccount, fileName, crAccountKeyPair);
      Assert.assertNotNull("Storage file id is null.", simpleStorageFileId);
      log.info("Smart Contract file uploaded successfully");
      ContractID sampleStorageContractId;

      sampleStorageContractId = createContractWithGasAndBalance(crAccount, simpleStorageFileId,
          CustomPropertiesSingleton.getInstance().getContractDuration(),
          CONTRACT_CREATE_SUCCESS_GAS, 0L, ResponseCodeEnum.SUCCESS);
      Assert.assertNotNull(sampleStorageContractId);
      Assert.assertNotEquals(0L, sampleStorageContractId.getContractNum());
      log.info("Contract created successfully");
      checkRecord(lastTx, crAccount, nodeAccount);

      Thread.sleep(2_000); // let payments settle out
      creatingAccountBalance = Utilities.getAccountBalance(balanceStub, crAccount,
          genesisAccount, genesisKeyPair, nodeAccount, TestHelper.getCryptoMaxFee());
      log.info("Creating account balance after successful create is " + creatingAccountBalance +
          ", spent " + (beforeBalance - creatingAccountBalance) + " on creation");
      beforeBalance = creatingAccountBalance;

      int currValueToSet = ThreadLocalRandom.current().nextInt(1, 1000000 + 1);
      expectedCost = invalidSetValueToContract(crAccount, sampleStorageContractId, currValueToSet);
      checkRecord(lastTx, crAccount, nodeAccount);

      Thread.sleep(2_000);
      creatingAccountBalance = Utilities.getAccountBalance(balanceStub, crAccount,
          genesisAccount, genesisKeyPair, nodeAccount, TestHelper.getCryptoMaxFee());
      log.info("Creating account balance after failed set is " + creatingAccountBalance +
          ", spent " + (beforeBalance - creatingAccountBalance) + " on set");

      Assert.assertEquals("Cost for set", expectedCost, (beforeBalance - creatingAccountBalance));
      beforeBalance = creatingAccountBalance;

      channel.shutdown();
    }
  }

  /**
   * Checks if record fields are instantiated.
   * 
   * @param transaction
   * @param payerID
   * @param nodeID
   * @throws Exception
   */
  public void checkRecord(Transaction transaction, AccountID payerID, AccountID nodeID) throws Exception {
    TransactionBody body = com.hedera.services.legacy.proto.utils.CommonUtils.extractTransactionBody(transaction);
    TransactionRecord record = getTransactionRecord(payerID, body.getTransactionID());
    CommonUtils.checkRecord(record, body);
  }

}
