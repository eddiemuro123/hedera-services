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

import com.google.common.cache.CacheBuilder;
import com.google.protobuf.ByteString;
import com.hedera.services.config.MockAccountNumbers;
import com.hedera.services.config.MockEntityNumbers;
import com.hedera.services.fees.StandardExemptions;
import com.hedera.services.legacy.services.context.ContextPlatformStatus;
import com.hedera.services.records.TxnIdRecentHistory;
import com.hedera.services.security.ops.SystemOpPolicies;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleTopic;
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.fees.HbarCentExchange;
import com.hedera.services.legacy.config.PropertiesLoader;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.legacy.handler.TransactionHandler;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleBlobMeta;
import com.hedera.services.state.merkle.MerkleOptionalBlob;
import com.hedera.services.legacy.service.FreezeServiceImpl;
import com.hedera.services.legacy.service.GlobalFlag;
import com.hedera.services.queries.validation.QueryFeeCheck;
import com.hedera.services.records.RecordCache;
import com.hedera.services.sigs.verification.PrecheckVerifier;
import com.hedera.services.txns.submission.PlatformSubmissionManager;
import com.hedera.services.txns.validation.BasicPrecheck;
import com.hedera.services.utils.MiscUtils;
import com.hedera.test.mocks.TestContextValidator;
import com.hedera.test.mocks.TestFeesFactory;
import com.hedera.test.mocks.TestProperties;
import com.hederahashgraph.api.proto.java.*;
import com.hederahashgraph.builder.RequestBuilder;
import com.hederahashgraph.builder.TransactionSigner;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.hedera.services.legacy.proto.utils.CommonUtils;
import com.swirlds.common.Platform;
import com.swirlds.common.PlatformStatus;
import com.swirlds.common.internal.SettingsCommon;
import com.swirlds.fcmap.FCMap;
import io.grpc.stub.StreamObserver;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import org.junit.BeforeClass;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.hedera.test.mocks.TestUsagePricesProvider.TEST_USAGE_PRICES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FREEZE_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.BDDMockito.*;

/**
 * Working directory should be hedera-node/
 */

@RunWith(JUnitPlatform.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FreezeServiceImplTest {

  @BeforeAll
  @BeforeClass
  public static void setupAll() {
    SettingsCommon.transactionMaxBytes = 1_234_567;
  }

  FCMap<MerkleEntityId, MerkleAccount> accountFCMap = null;
  FCMap<MerkleEntityId, MerkleTopic> topicFCMap = null;
  Transaction tx;
  Transaction signTransaction;
  Platform platform;
  long payerAccount;
  private TransactionHandler transactionHandler;
  private FreezeServiceImpl freezeService;
  private RecordCache receiptCache;
  private PlatformSubmissionManager submissionManager;
  private AccountID nodeAccountId;
  private Key key;
  private Map<String, PrivateKey> pubKey2privKeyMap;
  private FCMap<MerkleBlobMeta, MerkleOptionalBlob> storageMap = null;

  @BeforeAll
  public void setUp() throws Exception {
    payerAccount = 58;
    nodeAccountId = RequestBuilder.getAccountIdBuild(3l, 0l, 0l);

    submissionManager = mock(PlatformSubmissionManager.class);
    given(submissionManager.trySubmission(any())).willReturn(OK);

    //Init FCMap; Add account 58
    accountFCMap = new FCMap<>(new MerkleEntityId.Provider(), MerkleAccount.LEGACY_PROVIDER);
    MerkleEntityId mk = new MerkleEntityId();
    mk.setNum(payerAccount);
    mk.setRealm(0);

    MerkleAccount mv = new MerkleAccount();
    mv.setBalance(10000000000000000l);

    pubKey2privKeyMap = new HashMap<>();
    key = genSingleEd25519Key(pubKey2privKeyMap);
    mv.setKey(JKey.mapKey(key));
    accountFCMap.put(mk, mv);

    receiptCache = new RecordCache(
            null,
            CacheBuilder.newBuilder().build(),
            new HashMap<>());

    PrecheckVerifier precheckVerifier = mock(PrecheckVerifier.class);
    given(precheckVerifier.hasNecessarySignatures(any())).willReturn(true);
    HbarCentExchange exchange = new OneToOneRates();
    var policies = new SystemOpPolicies(new MockEntityNumbers());
    var platformStatus = new ContextPlatformStatus();
    platformStatus.set(PlatformStatus.ACTIVE);
    transactionHandler = new TransactionHandler(
            receiptCache,
            () -> accountFCMap,
            nodeAccountId,
            precheckVerifier,
            TEST_USAGE_PRICES,
            exchange,
            TestFeesFactory.FEES_FACTORY.getWithExchange(exchange),
            () -> new StateView(() -> topicFCMap, () -> accountFCMap),
            new BasicPrecheck(TestProperties.TEST_PROPERTIES, TestContextValidator.TEST_VALIDATOR),
            new QueryFeeCheck(() -> accountFCMap),
            new MockAccountNumbers(),
            policies,
            new StandardExemptions(new MockAccountNumbers(), policies),
            platformStatus);
    PropertyLoaderTest.populatePropertiesWithConfigFilesPath(
            "./configuration/dev/application.properties",
            "./configuration/dev/api-permission.properties");
    GlobalFlag.getInstance().setExchangeRateSet(getDefaultExchangeRateSet());
    freezeService = new FreezeServiceImpl(transactionHandler, submissionManager);

  }

  private static ExchangeRateSet getDefaultExchangeRateSet() {
    long expiryTime = Long.MAX_VALUE;
    return RequestBuilder.getExchangeRateSetBuilder(
            1, 1, expiryTime,
            1, 1, expiryTime);
  }

  private static class OneToOneRates implements HbarCentExchange {
    long expiryTime = Long.MAX_VALUE;
  	ExchangeRateSet rates = RequestBuilder.getExchangeRateSetBuilder(
  	        1, 1, expiryTime,
            1, 1, expiryTime);

    @Override
    public ExchangeRate activeRate() {
      return rates.getCurrentRate();
    }

    @Override
    public ExchangeRateSet activeRates() {
      return rates;
    }

    @Override
    public ExchangeRate rate(Timestamp at) {
      return rates.getCurrentRate();
    }
  }


  private static Key genSingleEd25519Key(Map<String, PrivateKey> pubKey2privKeyMap) {
    KeyPair pair = new KeyPairGenerator().generateKeyPair();
    byte[] pubKey = ((EdDSAPublicKey) pair.getPublic()).getAbyte();
    Key akey = Key.newBuilder().setEd25519(ByteString.copyFrom(pubKey)).build();
    String pubKeyHex = MiscUtils.commonsBytesToHex(pubKey);
    pubKey2privKeyMap.put(pubKeyHex, pair.getPrivate());
    return akey;
  }

  @Test
  public void freezeTest() throws Exception {
    tx = FreezeTestHelper.createFreezeTransaction(true, true, null);
    signTransaction = sign(tx);

    StreamObserver<TransactionResponse> responseObserver = new StreamObserver<>() {
      @Override
      public void onNext(TransactionResponse response) {
        System.out.println(response.getNodeTransactionPrecheckCode());
        Assertions.assertEquals( response.getNodeTransactionPrecheckCode() , OK);
      }

      @Override
      public void onError(Throwable t) {
        System.out.println("Error Happened " + t.getMessage());
      }

      @Override
      public void onCompleted() {
        System.out.println("Freeze Completed");
      }
    };

    TransactionID txID = CommonUtils.extractTransactionBody(tx).getTransactionID();
    freezeService.freeze(signTransaction, responseObserver);
    TransactionRecord record = TransactionRecord.newBuilder().setReceipt(
            TransactionReceipt.newBuilder().setStatus(OK))
            .build();
    receiptCache.setPostConsensus(
            txID,
            record.getReceipt().getStatus(),
            ExpirableTxnRecord.fromGprc(record));
  }

  /**
   * If a Freeze Transaction is not paid by account 55, precheck would return
   * ResponseCodeEnum.NOT_SUPPORTED
   */
  @Test
  public void freeze_NotPaidBy58_Test() throws Exception {
    Transaction freezeTx = FreezeTestHelper.createFreezeTransaction(false, true, null);
    Transaction signed = sign(freezeTx);
    StreamObserver<TransactionResponse> responseObserver = new StreamObserver<>() {
      @Override
      public void onNext(TransactionResponse response) {
        System.out.println(
                "Expecting NOT_SUPPORTED for freeze not paid by 58, got " +
                        response.getNodeTransactionPrecheckCode());
        Assertions.assertEquals(response.getNodeTransactionPrecheckCode() , ResponseCodeEnum.NOT_SUPPORTED);
      }

      @Override
      public void onError(Throwable t) {
        System.out.println("Error Happened " + t.getMessage());
      }

      @Override
      public void onCompleted() {
        System.out.println("Freeze Completed");
      }
    };

    freezeService.freeze(signed, responseObserver);
  }

  /**
   * If a Freeze Transaction is not valid, precheck would return ResponseCodeEnum.INVALID_FREEZE_TRANSACTION_BODY
   */
  @Test
  public void freeze_NotValidFreezeTxBody_Test() throws Exception {
    Transaction freezeTx = FreezeTestHelper.createFreezeTransaction(true, false, null);
    Transaction signed = sign(freezeTx);
    StreamObserver<TransactionResponse> responseObserver = new StreamObserver<>() {
      @Override
      public void onNext(TransactionResponse response) {
        System.out.println(response.getNodeTransactionPrecheckCode());
        Assertions.assertEquals(INVALID_FREEZE_TRANSACTION_BODY, response.getNodeTransactionPrecheckCode());
      }

      @Override
      public void onError(Throwable t) {
        System.out.println("Error Happened " + t.getMessage());
      }

      @Override
      public void onCompleted() {
        System.out.println("Freeze Completed");
      }
    };

    freezeService.freeze(signed, responseObserver);
  }

  /**
   * This is required to close all objects Else next set of test cases will fail
   */
  @AfterAll
  public void tearDown() {
    try {
    } catch (Throwable tx) {
      tx.printStackTrace();
    } finally {
    }
  }

  public Transaction sign(Transaction tx) throws Exception {
    SignatureMap sigsMap = TransactionSigner.signAsSignatureMap(
            tx.getBodyBytes().toByteArray(), Collections.singletonList(key), pubKey2privKeyMap);
    return tx.toBuilder().setSigMap(sigsMap).build();
  }
}
