package com.hedera.services.legacy.autorenew;

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
import com.hedera.services.legacy.client.util.Common;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ContractGetInfoResponse.ContractInfo;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.CryptoGetInfoResponse.AccountInfo;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.GetBySolidityIDResponse;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
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
import com.hederahashgraph.builder.TransactionSigner;
import com.hederahashgraph.service.proto.java.CryptoServiceGrpc;
import com.hederahashgraph.service.proto.java.SmartContractServiceGrpc;
import com.hedera.services.legacy.core.AccountKeyListObj;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.ethereum.core.CallTransaction;
import org.ethereum.util.ByteUtil;
import org.junit.Assert;

public class SmartContractAutoRenewCheck {

  private static long DAY_SEC_ACTUAL = 24 * 60 * 60; // secs in a day
  private static long DAY_SEC = 24 * 365 * 10 * 60 * 60 * 5;
  private static long INITIAL_BALANCE = TestHelper.getCryptoMaxFee() * 5;
  private final static String CONTRACT_MEMO_STRING_1 = "This is a memo string with non-Ascii characters: ȀĊ";
  private final static String CONTRACT_MEMO_STRING_2 = "This is an updated memo string.";
  private final static String CONTRACT_MEMO_STRING_3 = "Yet another memo.";
  private final Logger log = LogManager.getLogger(SmartContractAutoRenewCheck.class);


  private static final int MAX_RECEIPT_RETRIES = 60;
  private static final long MAX_TX_FEE = TestHelper.getCryptoMaxFee();
  private static final String SC_GET_ABI = "{\"constant\":true,\"inputs\":[],\"name\":\"get\",\"outputs\":[{\"name\":\"\",\"type\":\"uint256\"}],\"payable\":false,\"stateMutability\":\"view\",\"type\":\"function\"}";
  private static final String SC_SET_ABI = "{\"constant\":false,\"inputs\":[{\"name\":\"x\",\"type\":\"uint256\"}],\"name\":\"set\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"}";
  private static AccountID nodeAccount;
  private static long node_account_number;
  private static long node_shard_number;
  private static long node_realm_number;
  public static String INITIAL_ACCOUNTS_FILE = TestHelper.getStartUpFile();
  private AccountID genesisAccount;
  private Map<AccountID, KeyPair> accountKeyPairs = new HashMap<>();
  private static String host;
  private static int port;


  public static void main(String args[]) throws Exception {

    Properties properties = TestHelper.getApplicationProperties();
    host = properties.getProperty("host");
    port = Integer.parseInt(properties.getProperty("port"));
    node_account_number = 3l;
    node_shard_number = Long.parseLong(properties.getProperty("NODE_REALM_NUMBER"));
    node_realm_number = Long.parseLong(properties.getProperty("NODE_SHARD_NUMBER"));
    nodeAccount = AccountID.newBuilder().setAccountNum(node_account_number)
        .setRealmNum(node_shard_number).setShardNum(node_realm_number).build();

    int numberOfReps = 1;

    for (int i = 0; i < numberOfReps; i++) {
      SmartContractAutoRenewCheck scc = new SmartContractAutoRenewCheck();
      scc.demo();
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
    KeyPair genesisKeyPair = new KeyPair(genKeyPairObj.getPublicKey(), genesisPrivateKey);

    // get the Account Object
    genesisAccount = genesisAccountList.get(0).getAccountId();
    accountKeyPairs.put(genesisAccount, genesisKeyPair);
  }

  private Transaction createQueryHeaderTransfer(AccountID payer, long transferAmt)
      throws Exception {
    Transaction transferTx = TestHelper.createTransferSigMap(payer, accountKeyPairs.get(payer),
        nodeAccount, payer,
        accountKeyPairs.get(payer), nodeAccount, transferAmt);
    return transferTx;
  }

  private AccountID createAccount(AccountID payerAccount, long initialBalance) throws Exception {

    KeyPair keyGenerated = new KeyPairGenerator().generateKeyPair();
    return createAccount(keyGenerated, payerAccount, initialBalance);
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
        .getReceipt().getStatus().equals(ResponseCodeEnum.UNKNOWN)) {
      Thread.sleep(1000);
      transactionReceipts = stub.getTransactionReceipts(query);
      System.out.println("waiting to getTransactionReceipts as not Unknown..." +
          transactionReceipts.getTransactionGetReceipt().getReceipt().getStatus().name());
      attempts++;
    }
    channel.shutdown();
    Assert.assertEquals(ResponseCodeEnum.SUCCESS,
        transactionReceipts.getTransactionGetReceipt().getReceipt().getStatus());
    return transactionReceipts.getTransactionGetReceipt();

  }

  private ContractID createContractWithKey(AccountID payerAccount, FileID contractFile,
      long durationInSeconds, Key adminKey,
      AccountID adminAccount) throws Exception {
    ContractID createdContract = null;
    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();

    Duration contractAutoRenew = Duration.newBuilder().setSeconds(durationInSeconds).build();
    SmartContractServiceGrpc.SmartContractServiceBlockingStub stub = SmartContractServiceGrpc
        .newBlockingStub(channel);

    List<KeyPair> keyPairList = new ArrayList<>();
    keyPairList.add(accountKeyPairs.get(payerAccount));
    if (adminAccount != null && !adminAccount.equals(payerAccount)) {
      keyPairList.add(accountKeyPairs.get(adminAccount));
    }

    Timestamp timestamp = RequestBuilder
        .getTimestamp(Instant.now(Clock.systemUTC()).minusSeconds(13));
    Duration transactionDuration = RequestBuilder.getDuration(30);
    Transaction createContractRequest = TestHelper
        .getCreateContractRequestSigMap(payerAccount.getAccountNum(), payerAccount.getRealmNum(),
            payerAccount.getShardNum(), nodeAccount.getAccountNum(), nodeAccount.getRealmNum(),
            nodeAccount.getShardNum(), MAX_TX_FEE, timestamp,
            transactionDuration, true, "", 250000, contractFile, ByteString.EMPTY, 0,
            contractAutoRenew, keyPairList, CONTRACT_MEMO_STRING_1, adminKey);

    TransactionResponse response = stub.createContract(createContractRequest);
    System.out.println(
        " createContractWithKey Pre Check Response :: " + response.getNodeTransactionPrecheckCode()
            .name());
    TransactionBody createContractBody = TransactionBody
        .parseFrom(createContractRequest.getBodyBytes());
    TransactionGetReceiptResponse contractCreateReceipt = getReceipt(
        createContractBody.getTransactionID());
    if (contractCreateReceipt != null) {
      createdContract = contractCreateReceipt.getReceipt().getContractID();
    }
    channel.shutdown();

    return createdContract;
  }

  private void createContractWithKeyFail(AccountID payerAccount, FileID contractFile,
      long durationInSeconds, Key adminKey,
      AccountID adminAccount) throws Exception {
    ContractID createdContract = null;
    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();

    Duration contractAutoRenew = Duration.newBuilder().setSeconds(durationInSeconds).build();
    SmartContractServiceGrpc.SmartContractServiceBlockingStub stub = SmartContractServiceGrpc
        .newBlockingStub(channel);

    List<KeyPair> keyPairList = new ArrayList<>();
    keyPairList.add(accountKeyPairs.get(payerAccount));
    if (adminAccount != null && !adminAccount.equals(payerAccount)) {
      keyPairList.add(accountKeyPairs.get(adminAccount));
    }

    Timestamp timestamp = RequestBuilder
        .getTimestamp(Instant.now(Clock.systemUTC()).minusSeconds(13));
    Duration transactionDuration = RequestBuilder.getDuration(30);
    Transaction createContractRequest = TestHelper
        .getCreateContractRequestSigMap(payerAccount.getAccountNum(), payerAccount.getRealmNum(),
            payerAccount.getShardNum(), nodeAccount.getAccountNum(), nodeAccount.getRealmNum(),
            nodeAccount.getShardNum(), MAX_TX_FEE, timestamp,
            transactionDuration, true, "", 250000, contractFile, ByteString.EMPTY, 0,
            contractAutoRenew, keyPairList, CONTRACT_MEMO_STRING_1, adminKey);

    TransactionResponse response = stub.createContract(createContractRequest);
    log.info(response.getNodeTransactionPrecheckCode());
    Assert.assertEquals(ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE,
        response.getNodeTransactionPrecheckCode());
    channel.shutdown();
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

  private byte[] callContract(AccountID payerAccount, ContractID contractToCall, byte[] data)
      throws Exception {
    byte[] dataToReturn = null;
    ContractID createdContract = null;
    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();
    SmartContractServiceGrpc.SmartContractServiceBlockingStub stub = SmartContractServiceGrpc
        .newBlockingStub(channel);

    Timestamp timestamp = RequestBuilder
        .getTimestamp(Instant.now(Clock.systemUTC()).minusSeconds(13));
    Duration transactionDuration = RequestBuilder.getDuration(30);
    //payerAccountNum, payerRealmNum, payerShardNum, nodeAccountNum, nodeRealmNum, nodeShardNum, transactionFee, timestamp, txDuration, gas, contractId, functionData, value, signatures
    ByteString dataBstr = ByteString.EMPTY;
    if (data != null) {
      dataBstr = ByteString.copyFrom(data);
    }
    Transaction callContractRequest = TestHelper
        .getContractCallRequestSigMap(payerAccount.getAccountNum(), payerAccount.getRealmNum(),
            payerAccount.getShardNum(), 1l, 0l, 0l, MAX_TX_FEE, timestamp,
            transactionDuration, 250000, contractToCall, dataBstr, 0,
            accountKeyPairs.get(payerAccount));

    TransactionResponse response = stub.contractCallMethod(callContractRequest);
    System.out.println(
        " callContract Pre Check Response :: " + response.getNodeTransactionPrecheckCode()
            .name());
    Thread.sleep(1000);
    TransactionBody callContractBody = TransactionBody
        .parseFrom(callContractRequest.getBodyBytes());
    TransactionGetReceiptResponse contractCallReceipt = getReceipt(
        callContractBody.getTransactionID());
    if (contractCallReceipt != null && contractCallReceipt.getReceipt().getStatus()
            .equals(ResponseCodeEnum.SUCCESS)) {
      TransactionRecord trRecord = getTransactionRecord(payerAccount,
          callContractBody.getTransactionID());
      if (trRecord != null && trRecord.hasContractCallResult()) {
        ContractFunctionResult callResults = trRecord.getContractCallResult();
        String errMsg = callResults.getErrorMessage();
        if (StringUtils.isEmpty(errMsg)) {
          if (!callResults.getContractCallResult().isEmpty()) {
            dataToReturn = callResults.getContractCallResult().toByteArray();
          }
        } else {
          log.info("@@@ Contract Call resulted in error: " + errMsg);
        }
      }
    }
    channel.shutdown();

    return dataToReturn;
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
    System.out.println("tx record = " + txRecord);
    channel.shutdown();
    return txRecord;
  }


  private Response executeQueryForTxRecord(AccountID payerAccount, TransactionID transactionId,
      CryptoServiceGrpc.CryptoServiceBlockingStub stub, long fee, ResponseType responseType)
      throws Exception {
    Transaction paymentTx = createQueryHeaderTransfer(payerAccount, fee);
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
    Transaction paymentTx = createQueryHeaderTransfer(payerAccount, fee);

    Query contractCallLocal = RequestBuilder
        .getContractCallLocalQuery(contractToCall, 250000L, callData, 0L, 5000, paymentTx,
            ResponseType.COST_ANSWER);

    Response callResp = stub.contractCallLocalMethod(contractCallLocal);

    fee = callResp.getContractCallLocal().getHeader().getCost();
    paymentTx = createQueryHeaderTransfer(payerAccount, fee);
    contractCallLocal = RequestBuilder
        .getContractCallLocalQuery(contractToCall, 250000L, callData, 0L, 5000, paymentTx,
            ResponseType.ANSWER_ONLY);

    callResp = stub.contractCallLocalMethod(contractCallLocal);

    ByteString functionResults = callResp.getContractCallLocal().getFunctionResult()
        .getContractCallResult();

    System.out.println("callContractLocal response = " + callResp);
    channel.shutdown();
    return functionResults.toByteArray();
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


  private void setValueToContract(AccountID payerAccount, ContractID contractId, int valuetoSet)
      throws Exception {
    byte[] dataToSet = encodeSet(valuetoSet);
    //set value to simple storage smart contract
    byte[] retData = callContract(payerAccount, contractId, dataToSet);
  }

  public void updateContractWithKey(AccountID payerAccount, ContractID contractToUpdate,
      Duration autoRenewPeriod, Timestamp expirationTime, String contractMemo,
      AccountID adminAccount) throws Exception {

    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();
    SmartContractServiceGrpc.SmartContractServiceBlockingStub stub = SmartContractServiceGrpc
        .newBlockingStub(channel);

    List<Key> keyList = new ArrayList<>();
    HashMap<String, PrivateKey> pubKey2privKeyMap = new HashMap<>();
    KeyPair payerKeyPair = accountKeyPairs.get(payerAccount);
    keyList.add(Common.PrivateKeyToKey(payerKeyPair.getPrivate()));
    Common.addKeyMap(payerKeyPair, pubKey2privKeyMap);
    if (adminAccount != null && !adminAccount.equals(payerAccount)) {
      KeyPair adminKeyPair = accountKeyPairs.get(adminAccount);
      keyList.add(Common.PrivateKeyToKey(adminKeyPair.getPrivate()));
      Common.addKeyMap(adminKeyPair, pubKey2privKeyMap);
    }

    Timestamp timestamp = RequestBuilder
        .getTimestamp(Instant.now(Clock.systemUTC()).minusSeconds(13));
    Duration transactionDuration = RequestBuilder.getDuration(30);
    Transaction updateContractRequest = RequestBuilder
        .getContractUpdateRequest(payerAccount, nodeAccount, MAX_TX_FEE, timestamp,
            transactionDuration, true, "", contractToUpdate, autoRenewPeriod, null, null,
            expirationTime,
            contractMemo);
    updateContractRequest = TransactionSigner
        .signTransactionComplexWithSigMap(updateContractRequest, keyList, pubKey2privKeyMap);

    TransactionResponse response = stub.updateContract(updateContractRequest);
    System.out.println(
        " update contract Pre Check Response :: " + response.getNodeTransactionPrecheckCode()
            .name());
    Assert.assertEquals(response.getNodeTransactionPrecheckCode(),
        ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE);
    Thread.sleep(1000);
    channel.shutdown();

  }

  private String getContractByteCode(AccountID payerAccount,
      ContractID contractId) throws Exception {
    String byteCode = "";
    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();
    SmartContractServiceGrpc.SmartContractServiceBlockingStub stub = SmartContractServiceGrpc
        .newBlockingStub(channel);

    long fee = FeeClient.getFeeByID(HederaFunctionality.ContractGetBytecode);
    Response respToReturn = executeContractByteCodeQuery(payerAccount, contractId, stub, fee,
        ResponseType.COST_ANSWER);

    fee = respToReturn.getContractGetBytecodeResponse().getHeader().getCost();
    respToReturn = executeContractByteCodeQuery(payerAccount, contractId, stub, fee,
        ResponseType.ANSWER_ONLY);
    ByteString contractByteCode = null;
    contractByteCode = respToReturn.getContractGetBytecodeResponse().getBytecode();
    if (contractByteCode != null && !contractByteCode.isEmpty()) {
      byteCode = ByteUtil.toHexString(contractByteCode.toByteArray());
    }
    channel.shutdown();

    return byteCode;
  }


  private Response executeContractByteCodeQuery(AccountID payerAccount, ContractID contractId,
      SmartContractServiceGrpc.SmartContractServiceBlockingStub stub, long fee,
      ResponseType responseType) throws Exception {
    Transaction paymentTx = createQueryHeaderTransfer(payerAccount, fee);
    Query getContractBytecodeQuery = RequestBuilder
        .getContractGetBytecodeQuery(contractId, paymentTx, responseType);
    Response respToReturn = stub.contractGetBytecode(getContractBytecodeQuery);
    return respToReturn;
  }

  private AccountInfo getCryptoGetAccountInfo(
      AccountID accountID) throws Exception {
    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();
    CryptoServiceGrpc.CryptoServiceBlockingStub stub = CryptoServiceGrpc.newBlockingStub(channel);
    long fee = FeeClient.getFeeByID(HederaFunctionality.CryptoGetInfo);

    Response respToReturn = executeGetAcctInfoQuery(accountID, stub, fee, ResponseType.COST_ANSWER);

    fee = respToReturn.getCryptoGetInfo().getHeader().getCost();
    respToReturn = executeGetAcctInfoQuery(accountID, stub, fee, ResponseType.ANSWER_ONLY);
    AccountInfo accInfToReturn = null;
    accInfToReturn = respToReturn.getCryptoGetInfo().getAccountInfo();
    channel.shutdown();

    return accInfToReturn;
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

  private GetBySolidityIDResponse getBySolidityID(AccountID payerAccount,
      String solidityId) throws Exception {
    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();
    SmartContractServiceGrpc.SmartContractServiceBlockingStub stub = SmartContractServiceGrpc
        .newBlockingStub(channel);
    long fee = FeeClient.getFeegetBySolidityID();
    Transaction paymentTx = createQueryHeaderTransfer(payerAccount, fee);
    Query getBySolidityIdQuery = RequestBuilder
        .getBySolidityIDQuery(solidityId, paymentTx, ResponseType.ANSWER_ONLY);

    Response respToReturn = stub.getBySolidityID(getBySolidityIdQuery);
    GetBySolidityIDResponse bySolidityReturn = null;
    bySolidityReturn = respToReturn.getGetBySolidityID();
    channel.shutdown();

    return bySolidityReturn;
  }


  public void demo() throws Exception {
    loadGenesisAndNodeAcccounts();

    KeyPair adminKeyPair = new KeyPairGenerator().generateKeyPair();
    byte[] pubKey = ((EdDSAPublicKey) adminKeyPair.getPublic()).getAbyte();
    Key adminPubKey = Key.newBuilder().setEd25519(ByteString.copyFrom(pubKey)).build();
    AccountID adminAccount = createAccount(adminKeyPair, genesisAccount, INITIAL_BALANCE);

    KeyPair crAccountKeyPair = new KeyPairGenerator().generateKeyPair();
    AccountID crAccount = createAccount(crAccountKeyPair, genesisAccount, INITIAL_BALANCE);
    log.info("Account created successfully " + crAccount.getAccountNum());
    String fileName = "simpleStorage.bin";
    if (crAccount != null) {

      FileID simpleStorageFileId = LargeFileUploadIT
          .uploadFile(crAccount, fileName, crAccountKeyPair);
      if (simpleStorageFileId != null) {
        log.info("Smart Contract file uploaded successfully " + simpleStorageFileId.getFileNum());
        ContractID sampleStorageContractId = createContractWithKey(crAccount, simpleStorageFileId,
            DAY_SEC_ACTUAL * 30, adminPubKey, adminAccount);
        Assert.assertNotNull(sampleStorageContractId);
        Assert.assertNotEquals(0L, sampleStorageContractId.getContractNum());
        log.info("Contract created successfully");
        ContractInfo cInfo1 = getContractInfo(crAccount, sampleStorageContractId);
        Assert.assertEquals("Contract memo mismatch.", CONTRACT_MEMO_STRING_1, cInfo1.getMemo());
        log.info("Original expiration in seconds: " + cInfo1.getExpirationTime().getSeconds());

        log.info("Original admin public key: " + adminPubKey.toString());
        log.info("Returned admin public key: " + cInfo1.getAdminKey().toString());
        Assert.assertEquals(cInfo1.getAdminKey().toString(), adminPubKey.toString());

        // Update 1: Update and check both expiration and memo
        Instant c1Expiration = Instant.ofEpochSecond(cInfo1.getExpirationTime().getSeconds(),
            cInfo1.getExpirationTime().getNanos());
        Date c1ExpirationDate = Date.from(c1Expiration);
        Thread.sleep(10000);
        Timestamp c1NewExpirationDate = Timestamp.newBuilder()
            .setSeconds(cInfo1.getExpirationTime().getSeconds() + DAY_SEC_ACTUAL * 30).build();
        updateContractWithKey(crAccount, sampleStorageContractId,
            Duration.newBuilder().setSeconds(DAY_SEC * 30).build(), c1NewExpirationDate,
            CONTRACT_MEMO_STRING_2, adminAccount);

        ContractInfo c1InfoAfterUpdate = getContractInfo(crAccount, sampleStorageContractId);
        Timestamp c1ExpirationDateAfterUpdate = c1InfoAfterUpdate.getExpirationTime();

        log.info(
            "Updated expiration in seconds: " + c1InfoAfterUpdate.getExpirationTime().getSeconds());
        log.info("Updated memo: " + c1InfoAfterUpdate.getMemo());
      }
    }

    if (crAccount != null) {

      FileID simpleStorageFileId = LargeFileUploadIT
          .uploadFile(crAccount, fileName, crAccountKeyPair);
      if (simpleStorageFileId != null) {
        log.info("Smart Contract file uploaded successfully " + simpleStorageFileId.getFileNum());
        createContractWithKeyFail(crAccount, simpleStorageFileId,
            DAY_SEC * 30, adminPubKey, adminAccount);
      }
    }
  }

  private ContractInfo getContractInfo(AccountID payerAccount,
      ContractID contractId) throws Exception {
    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();
    SmartContractServiceGrpc.SmartContractServiceBlockingStub stub = SmartContractServiceGrpc
        .newBlockingStub(channel);
    long fee = FeeClient.getFeeByID(HederaFunctionality.ContractGetInfo);

    Response respToReturn = executeGetContractInfo(payerAccount, contractId, stub, fee,
        ResponseType.COST_ANSWER);

    fee = respToReturn.getContractGetInfo().getHeader().getCost();
    respToReturn = executeGetContractInfo(payerAccount, contractId, stub, fee,
        ResponseType.ANSWER_ONLY);
    ContractInfo contractInfToReturn = null;
    contractInfToReturn = respToReturn.getContractGetInfo().getContractInfo();
    channel.shutdown();

    return contractInfToReturn;
  }


  private Response executeGetContractInfo(AccountID payerAccount, ContractID contractId,
      SmartContractServiceGrpc.SmartContractServiceBlockingStub stub, long fee,
      ResponseType responseType) throws Exception {
    Transaction paymentTx = createQueryHeaderTransfer(payerAccount, fee);

    Query getContractInfoQuery = RequestBuilder
        .getContractGetInfoQuery(contractId, paymentTx, responseType);

    Response respToReturn = stub.getContractInfo(getContractInfoQuery);
    return respToReturn;
  }


}
