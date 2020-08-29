package com.hedera.services.utils;

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

import com.google.common.io.Files;
import com.google.protobuf.ByteString;
import com.hedera.services.exceptions.UnknownHederaFunctionality;
import com.hedera.services.keys.LegacyEd25519KeyReader;
import com.hedera.services.legacy.core.jproto.JEd25519Key;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ConsensusCreateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.ConsensusDeleteTopicTransactionBody;
import com.hederahashgraph.api.proto.java.ConsensusGetTopicInfoQuery;
import com.hederahashgraph.api.proto.java.ConsensusSubmitMessageTransactionBody;
import com.hederahashgraph.api.proto.java.ConsensusUpdateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.ContractCallLocalQuery;
import com.hederahashgraph.api.proto.java.ContractCallTransactionBody;
import com.hederahashgraph.api.proto.java.ContractCreateTransactionBody;
import com.hederahashgraph.api.proto.java.ContractDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.ContractGetBytecodeQuery;
import com.hederahashgraph.api.proto.java.ContractGetInfoQuery;
import com.hederahashgraph.api.proto.java.ContractGetRecordsQuery;
import com.hederahashgraph.api.proto.java.ContractUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoAddLiveHashTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoDeleteLiveHashTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoGetAccountBalanceQuery;
import com.hederahashgraph.api.proto.java.CryptoGetAccountRecordsQuery;
import com.hederahashgraph.api.proto.java.CryptoGetLiveHashQuery;
import com.hederahashgraph.api.proto.java.CryptoGetInfoQuery;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.FileAppendTransactionBody;
import com.hederahashgraph.api.proto.java.FileCreateTransactionBody;
import com.hederahashgraph.api.proto.java.FileDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.FileGetContentsQuery;
import com.hederahashgraph.api.proto.java.FileGetInfoQuery;
import com.hederahashgraph.api.proto.java.FileUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.FreezeTransactionBody;
import com.hederahashgraph.api.proto.java.GetByKeyQuery;
import com.hederahashgraph.api.proto.java.GetBySolidityIDQuery;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.NetworkGetVersionInfoQuery;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.SystemDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.SystemUndeleteTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionGetFastRecordQuery;
import com.hederahashgraph.api.proto.java.TransactionGetReceiptQuery;
import com.hederahashgraph.api.proto.java.TransactionGetRecordQuery;
import com.hederahashgraph.api.proto.java.TransactionReceipt;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.api.proto.java.TransferList;
import com.hedera.services.legacy.core.AccountKeyListObj;
import com.hedera.services.legacy.core.KeyPairObj;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.hedera.services.legacy.proto.utils.CommonUtils;
import com.hederahashgraph.api.proto.java.UncheckedSubmitBody;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;

import static com.hedera.test.utils.IdUtils.*;
import static com.hedera.test.utils.TxnUtils.*;
import com.google.protobuf.GeneratedMessageV3;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.hedera.services.utils.MiscUtils.*;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_ONLY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;
import static com.hedera.services.state.submerkle.ExpirableTxnRecord.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RunWith(JUnitPlatform.class)
public class MiscUtilsTest {
	@Test
	public void asFcKeyUncheckedTranslatesExceptions() {
		// expect:
		assertThrows(IllegalArgumentException.class,
				() -> MiscUtils.asFcKeyUnchecked(Key.getDefaultInstance()));
	}

	@Test
	public void asFcKeyReturnsEmptyOnUnparseableKey() {
		// expect:
		assertTrue(asUsableFcKey(Key.getDefaultInstance()).isEmpty());
	}

	@Test
	public void asFcKeyReturnsEmptyOnEmptyKey() {
		// expect:
		assertTrue(asUsableFcKey(Key.newBuilder().setKeyList(KeyList.getDefaultInstance()).build()).isEmpty());
	}

	@Test
	public void asFcKeyReturnsEmptyOnInvalidKey() {
		// expect:
		assertTrue(asUsableFcKey(Key.newBuilder().setEd25519(ByteString.copyFrom("1".getBytes())).build()).isEmpty());
	}

	@Test
	public void asFcKeyReturnsExpected() {
		// given:
		var key = Key.newBuilder().setEd25519(ByteString.copyFrom(
				"01234567890123456789012345678901".getBytes())).build();

		// expect:
		assertTrue(JKey.equalUpToDecodability(
				asUsableFcKey(key).get(),
				MiscUtils.asFcKeyUnchecked(key)));
	}

	@Test
	public void asFcKeyUncheckedWorks() {
		// setup:
		byte[] fakePrivateKey = "not-really-a-key!".getBytes();
		// and:
		Key matchingKey = Key.newBuilder().setEd25519(ByteString.copyFrom(fakePrivateKey)).build();

		// given:
		var expected = new JEd25519Key(fakePrivateKey);

		// expect:
		assertTrue(JKey.equalUpToDecodability(expected, MiscUtils.asFcKeyUnchecked(matchingKey)));
	}

	@Test
	public void recoversKeypair() throws Exception {
		// setup:
		String tmpLoc = "src/test/resources/PretendKeystore.txt";

		// given:
		KeyPair kp = new KeyPairGenerator().generateKeyPair();
		byte[] expected = ((EdDSAPublicKey)kp.getPublic()).getAbyte();
		// and:
		writeB64EncodedKeyPair(new File(tmpLoc), kp);

		// when:
		var masterKey = lookupInCustomStore(new LegacyEd25519KeyReader(), tmpLoc, "START_ACCOUNT");

		// then:
		assertArrayEquals(expected, masterKey.getEd25519());
		(new File(tmpLoc)).delete();
	}

	@Test
	public void getsCanonicalDiff() {
		AccountID a = asAccount("1.2.3");
		AccountID b = asAccount("2.3.4");
		AccountID c = asAccount("3.4.5");

		// given:
		List<AccountAmount> canonicalA = List.of(
				aa(a, 300),
				aa(b, -500),
				aa(c, 200));
		// and:
		List<AccountAmount> canonicalB = List.of(
				aa(a, 150),
				aa(b, 50),
				aa(c, -200));

		// when:
		List<AccountAmount> canonicalDiff = canonicalDiffRepr(canonicalA, canonicalB);

		// then:
		assertThat(
				canonicalDiff,
				contains(aa(a, 150), aa(b, -550), aa(c, 400)));
	}

	@Test
	public void getsCanonicalRepr() {
		AccountID a = asAccount("1.2.3");
		AccountID b = asAccount("2.3.4");
		AccountID c = asAccount("3.4.5");

		// given:
		List<AccountAmount> adhocRepr = List.of(
				aa(a, 500),
				aa(c, 100),
				aa(a, -500),
				aa(b, -500),
				aa(c, 400));

		// when:
		List<AccountAmount> canonicalRepr = canonicalRepr(adhocRepr);

		// then:
		assertThat(
				canonicalRepr,
				contains(aa(b, -500), aa(c, 500)));
	}

	private AccountAmount aa(AccountID who, long what) {
		return AccountAmount.newBuilder().setAccountID(who).setAmount(what).build();
	}

	@Test
	public void prettyPrintsTransferList() {
		// given:
		TransferList transfers = withAdjustments(
				asAccount("0.1.2"), 500L,
				asAccount("1.0.2"), -250L,
				asAccount("1.2.0"), Long.MIN_VALUE);

		// when:
		String s = readableTransferList(transfers);

		assertEquals("[0.1.2 <- +500, 1.0.2 -> -250, 1.2.0 -> -9223372036854775808]", s);
	}

	@Test
	public void prettyPrintsJTransactionRecordFcll() {
		// given:
		LinkedList<ExpirableTxnRecord> records = new LinkedList<>();
		records.add(fromGprc(
				TransactionRecord.newBuilder()
						.setReceipt(TransactionReceipt.newBuilder().setStatus(SUCCESS))
						.build()));
		records.add(fromGprc(
				TransactionRecord.newBuilder()
						.setReceipt(TransactionReceipt.newBuilder().setStatus(INVALID_ACCOUNT_ID))
						.build()));

		// expect:
		Assertions.assertDoesNotThrow(() -> System.out.println(readableProperty(records)));
	}

	@Test
	public void throwsOnUnexpectedFunctionality() {
		// expect:
		assertThrows(UnknownHederaFunctionality.class, () -> {
			functionOf(TransactionBody.getDefaultInstance());
		});
	}

	@Test
	public void getExpectedTxnStat() {
		Map<String, BodySetter<? extends GeneratedMessageV3>> setters = new HashMap<>() {{
			put("createAccount", new BodySetter<>(CryptoCreateTransactionBody.class));
			put("updateAccount", new BodySetter<>(CryptoUpdateTransactionBody.class));
			put("cryptoTransfer", new BodySetter<>(CryptoTransferTransactionBody.class));
			put("cryptoDelete", new BodySetter<>(CryptoDeleteTransactionBody.class));
			put("createContract", new BodySetter<>(ContractCreateTransactionBody.class));
			put("contractCallMethod", new BodySetter<>(ContractCallTransactionBody.class));
			put("updateContract", new BodySetter<>(ContractUpdateTransactionBody.class));
			put("deleteContract", new BodySetter<>(ContractDeleteTransactionBody.class));
			put("addLiveHash", new BodySetter<>(CryptoAddLiveHashTransactionBody.class));
			put("deleteLiveHash", new BodySetter<>(CryptoDeleteLiveHashTransactionBody.class));
			put("createFile", new BodySetter<>(FileCreateTransactionBody.class));
			put("appendContent", new BodySetter<>(FileAppendTransactionBody.class));
			put("updateFile", new BodySetter<>(FileUpdateTransactionBody.class));
			put("deleteFile", new BodySetter<>(FileDeleteTransactionBody.class));
			put("systemDelete", new BodySetter<>(SystemDeleteTransactionBody.class));
			put("systemUndelete", new BodySetter<>(SystemUndeleteTransactionBody.class));
			put("freeze", new BodySetter<>(FreezeTransactionBody.class));
			put("createTopic", new BodySetter<>(ConsensusCreateTopicTransactionBody.class));
			put("updateTopic", new BodySetter<>(ConsensusUpdateTopicTransactionBody.class));
			put("deleteTopic", new BodySetter<>(ConsensusDeleteTopicTransactionBody.class));
			put("submitMessage", new BodySetter<>(ConsensusSubmitMessageTransactionBody.class));
		}};

		// expect:
		setters.forEach((stat, setter) -> {
			TransactionBody.Builder txn = TransactionBody.newBuilder();
			setter.setDefaultInstanceOnTxn(txn);
			assertEquals(stat, getTxnStat(txn.build()));
		});
		// and:
		assertEquals("NotImplemented", getTxnStat(TransactionBody.getDefaultInstance()));
	}

	@Test
	public void recognizesMissingQueryCase() {
		// expect:
		assertTrue(functionalityOfQuery(Query.getDefaultInstance()).isEmpty());
	}

	@Test
	public void getsExpectedQueryFunctionality() {
		// setup:
		Map<HederaFunctionality, BodySetter<? extends GeneratedMessageV3>> setters = new HashMap<>() {{
			put(GetVersionInfo, new BodySetter<>(NetworkGetVersionInfoQuery.class));
			put(GetByKey, new BodySetter<>(GetByKeyQuery.class));
			put(ConsensusGetTopicInfo, new BodySetter<>(ConsensusGetTopicInfoQuery.class));
			put(GetBySolidityID, new BodySetter<>(GetBySolidityIDQuery.class));
			put(ContractCallLocal, new BodySetter<>(ContractCallLocalQuery.class));
			put(ContractGetInfo, new BodySetter<>(ContractGetInfoQuery.class));
			put(ContractGetBytecode, new BodySetter<>(ContractGetBytecodeQuery.class));
			put(ContractGetRecords, new BodySetter<>(ContractGetRecordsQuery.class));
			put(CryptoGetAccountBalance, new BodySetter<>(CryptoGetAccountBalanceQuery.class));
			put(CryptoGetAccountRecords, new BodySetter<>(CryptoGetAccountRecordsQuery.class));
			put(CryptoGetInfo, new BodySetter<>(CryptoGetInfoQuery.class));
			put(CryptoGetLiveHash, new BodySetter<>(CryptoGetLiveHashQuery.class));
			put(FileGetContents, new BodySetter<>(FileGetContentsQuery.class));
			put(FileGetInfo, new BodySetter<>(FileGetInfoQuery.class));
			put(TransactionGetReceipt, new BodySetter<>(TransactionGetReceiptQuery.class));
			put(TransactionGetRecord, new BodySetter<>(TransactionGetRecordQuery.class));
		}};

		// expect:
		setters.forEach((function, setter) -> {
			Query.Builder query = Query.newBuilder();
			setter.setDefaultInstanceOnQuery(query);
			assertEquals(function, functionalityOfQuery(query.build()).get());
		});
	}

	@Test
	public void worksForGetVersionInfo() {
		var op = NetworkGetVersionInfoQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setNetworkGetVersionInfo(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForGetTopicInfo() {
		var op = ConsensusGetTopicInfoQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setConsensusGetTopicInfo(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForGetSolidityId() {
		var op = GetBySolidityIDQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setGetBySolidityID(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForGetContractCallLocal() {
		var op = ContractCallLocalQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setContractCallLocal(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForGetContractInfo() {
		var op = ContractGetInfoQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setContractGetInfo(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForGetContractBytecode() {
		var op = ContractGetBytecodeQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setContractGetBytecode(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForGetContractRecords() {
		var op = ContractGetRecordsQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setContractGetRecords(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForGetCryptoBalance() {
		var op = CryptoGetAccountBalanceQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setCryptogetAccountBalance(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForGetCryptoRecords() {
		var op = CryptoGetAccountRecordsQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setCryptoGetAccountRecords(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForGetCryptoInfo() {
		var op = CryptoGetInfoQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setCryptoGetInfo(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForGetLiveHash() {
		var op = CryptoGetLiveHashQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setCryptoGetLiveHash(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForGetFileContents() {
		var op = FileGetContentsQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setFileGetContents(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForGetFileinfo() {
		var op = FileGetInfoQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setFileGetInfo(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForReceipt() {
		var op = TransactionGetReceiptQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setTransactionGetReceipt(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForRecord() {
		var op = TransactionGetRecordQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setTransactionGetRecord(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void worksForEmpty() {
		assertTrue(activeHeaderFrom(Query.getDefaultInstance()).isEmpty());
	}

	@Test
	public void worksForFastRecord() {
		var op = TransactionGetFastRecordQuery.newBuilder()
				.setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
		var query = Query.newBuilder()
				.setTransactionGetFastRecord(op)
				.build();
		assertEquals(ANSWER_ONLY, activeHeaderFrom(query).get().getResponseType());
	}

	@Test
	public void getsExpectedTxnFunctionality() {
		// setup:
		Map<HederaFunctionality, BodySetter<? extends GeneratedMessageV3>> setters = new HashMap<>() {{
			put(SystemDelete, new BodySetter<>(SystemDeleteTransactionBody.class));
			put(SystemUndelete, new BodySetter<>(SystemUndeleteTransactionBody.class));
			put(ContractCall, new BodySetter<>(ContractCallTransactionBody.class));
			put(ContractCreate, new BodySetter<>(ContractCreateTransactionBody.class));
			put(ContractUpdate, new BodySetter<>(ContractUpdateTransactionBody.class));
			put(CryptoAddLiveHash, new BodySetter<>(CryptoAddLiveHashTransactionBody.class));
			put(CryptoCreate, new BodySetter<>(CryptoCreateTransactionBody.class));
			put(CryptoDelete, new BodySetter<>(CryptoDeleteTransactionBody.class));
			put(CryptoDeleteLiveHash, new BodySetter<>(CryptoDeleteLiveHashTransactionBody.class));
			put(CryptoTransfer, new BodySetter<>(CryptoTransferTransactionBody.class));
			put(CryptoUpdate, new BodySetter<>(CryptoUpdateTransactionBody.class));
			put(FileAppend, new BodySetter<>(FileAppendTransactionBody.class));
			put(FileCreate, new BodySetter<>(FileCreateTransactionBody.class));
			put(FileDelete, new BodySetter<>(FileDeleteTransactionBody.class));
			put(FileUpdate, new BodySetter<>(FileUpdateTransactionBody.class));
			put(ContractDelete, new BodySetter<>(ContractDeleteTransactionBody.class));
			put(Freeze, new BodySetter<>(FreezeTransactionBody.class));
			put(ConsensusCreateTopic, new BodySetter<>(ConsensusCreateTopicTransactionBody.class));
			put(ConsensusUpdateTopic, new BodySetter<>(ConsensusUpdateTopicTransactionBody.class));
			put(ConsensusDeleteTopic, new BodySetter<>(ConsensusDeleteTopicTransactionBody.class));
			put(ConsensusSubmitMessage, new BodySetter<>(ConsensusSubmitMessageTransactionBody.class));
			put(UncheckedSubmit, new BodySetter<>(UncheckedSubmitBody.class));
		}};

		// expect:
		setters.forEach((function, setter) -> {
			TransactionBody.Builder txn = TransactionBody.newBuilder();
			setter.setDefaultInstanceOnTxn(txn);
			try {
				assertEquals(function, functionOf(txn.build()));
			} catch (UnknownHederaFunctionality uhf) {
				throw new IllegalStateException(uhf);
			}
		});
	}

	public static class BodySetter<T> {
		private final Class<T> type;

		public BodySetter(Class<T> type) {
			this.type = type;
		}

		public void setDefaultInstanceOnQuery(Query.Builder query) {
			try {
				Method setter = Stream.of(Query.Builder.class.getDeclaredMethods())
						.filter(m -> m.getName().startsWith("set") && m.getParameterTypes()[0].equals(type))
						.findFirst()
						.get();
				Method defaultGetter = type.getMethod("getDefaultInstance");
				T defaultInstance = (T)defaultGetter.invoke(null);
				setter.invoke(query, defaultInstance);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

		public void setDefaultInstanceOnTxn(TransactionBody.Builder txn) {
			try {
				Method setter = Stream.of(TransactionBody.Builder.class.getDeclaredMethods())
						.filter(m -> m.getName().startsWith("set") && m.getParameterTypes()[0].equals(type))
						.findFirst()
						.get();
				Method defaultGetter = type.getMethod("getDefaultInstance");
				T defaultInstance = (T)defaultGetter.invoke(null);
				setter.invoke(txn, defaultInstance);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private static void writeB64EncodedKeyPair(File file, KeyPair keyPair) throws Exception {
		var hexPublicKey = Hex.encodeHexString(keyPair.getPublic().getEncoded());
		var hexPrivateKey = Hex.encodeHexString(keyPair.getPrivate().getEncoded());
		var keyPairObj = new KeyPairObj(hexPublicKey, hexPrivateKey);
		var keys = new AccountKeyListObj(asAccount("0.0.2"), List.of(keyPairObj));

		var baos = new ByteArrayOutputStream();
		var oos = new ObjectOutputStream(baos);
		oos.writeObject(Map.of("START_ACCOUNT", List.of(keys)));
		oos.close();

		var byteSink = Files.asByteSink(file);
		byteSink.write(CommonUtils.base64encode(baos.toByteArray()).getBytes());
	}
}
