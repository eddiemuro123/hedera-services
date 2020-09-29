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

import com.hedera.services.exceptions.UnknownHederaFunctionality;
import static com.hedera.services.grpc.controllers.CryptoController.*;
import static com.hedera.services.grpc.controllers.ConsensusController.*;
import static com.hedera.services.grpc.controllers.TokenController.*;
import static com.hedera.services.grpc.controllers.FileController.*;

import com.hedera.services.keys.LegacyEd25519KeyReader;
import com.hedera.services.ledger.HederaLedger;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransferList;
import com.hedera.services.legacy.core.jproto.JEd25519Key;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.swirlds.common.AddressBook;
import com.swirlds.fcqueue.FCQueue;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.hedera.services.utils.EntityIdUtils.accountParsedFromString;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.CONSENSUSGETTOPICINFO;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.CONTRACTCALLLOCAL;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.CONTRACTGETBYTECODE;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.CONTRACTGETINFO;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.CONTRACTGETRECORDS;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.CRYPTOGETACCOUNTBALANCE;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.CRYPTOGETACCOUNTRECORDS;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.CRYPTOGETLIVEHASH;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.CRYPTOGETINFO;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.FILEGETCONTENTS;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.FILEGETINFO;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.GETBYKEY;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.GETBYSOLIDITYID;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.NETWORKGETVERSIONINFO;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.TOKENGETINFO;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.TRANSACTIONGETRECEIPT;
import static com.hederahashgraph.api.proto.java.Query.QueryCase.TRANSACTIONGETRECORD;
import static com.hedera.services.legacy.core.jproto.JKey.mapJKey;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.*;
import static java.util.stream.Collectors.toSet;

public class MiscUtils {
	private static final EnumMap<Query.QueryCase, HederaFunctionality> queryFunctions =
			new EnumMap<>(Query.QueryCase.class);
	static {
		queryFunctions.put(NETWORKGETVERSIONINFO, GetVersionInfo);
		queryFunctions.put(GETBYKEY, GetByKey);
		queryFunctions.put(CONSENSUSGETTOPICINFO, ConsensusGetTopicInfo);
		queryFunctions.put(GETBYSOLIDITYID, GetBySolidityID);
		queryFunctions.put(CONTRACTCALLLOCAL, ContractCallLocal);
		queryFunctions.put(CONTRACTGETINFO, ContractGetInfo);
		queryFunctions.put(CONTRACTGETBYTECODE, ContractGetBytecode);
		queryFunctions.put(CONTRACTGETRECORDS, ContractGetRecords);
		queryFunctions.put(CRYPTOGETACCOUNTBALANCE, CryptoGetAccountBalance);
		queryFunctions.put(CRYPTOGETACCOUNTRECORDS, CryptoGetAccountRecords);
		queryFunctions.put(CRYPTOGETINFO, CryptoGetInfo);
		queryFunctions.put(CRYPTOGETLIVEHASH, CryptoGetLiveHash);
		queryFunctions.put(FILEGETCONTENTS, FileGetContents);
		queryFunctions.put(FILEGETINFO, FileGetInfo);
		queryFunctions.put(TRANSACTIONGETRECEIPT, TransactionGetReceipt);
		queryFunctions.put(TRANSACTIONGETRECORD, TransactionGetRecord);
		queryFunctions.put(TOKENGETINFO, TokenGetInfo);
	}

	public static List<AccountAmount> canonicalDiffRepr(List<AccountAmount> a, List<AccountAmount> b) {
		return canonicalRepr(Stream.concat(a.stream(), b.stream().map(MiscUtils::negationOf)).collect(toList()));
	}

	private static AccountAmount negationOf(AccountAmount adjustment) {
		return adjustment.toBuilder().setAmount(-1 * adjustment.getAmount()).build();
	}

	public static List<AccountAmount> canonicalRepr(List<AccountAmount> transfers) {
		return transfers.stream()
				.collect(toMap(AccountAmount::getAccountID, AccountAmount::getAmount, Math::addExact))
				.entrySet().stream()
				.filter(e -> e.getValue() != 0)
				.sorted(comparing(Map.Entry::getKey, HederaLedger.ACCOUNT_ID_COMPARATOR))
				.map(e -> AccountAmount.newBuilder().setAccountID(e.getKey()).setAmount(e.getValue()).build())
				.collect(toList());
	}

	public static String readableTransferList(TransferList accountAmounts) {
		return accountAmounts.getAccountAmountsList()
				.stream()
				.map(aa -> String.format(
						"%s %s %s%s",
						EntityIdUtils.readableId(aa.getAccountID()),
						aa.getAmount() < 0 ? "->" : "<-",
						aa.getAmount() < 0 ? "-" : "+",
						BigInteger.valueOf(aa.getAmount()).abs().toString()))
				.collect(toList())
				.toString();
	}

	public static JKey lookupInCustomStore(LegacyEd25519KeyReader b64Reader, String storeLoc, String kpId) {
		try {
			return new JEd25519Key(commonsHexToBytes(b64Reader.hexedABytesFrom(storeLoc, kpId)));
		} catch (DecoderException e) {
			throw new IllegalArgumentException(e);
		}
	}

	public static String readableProperty(Object o) {
		if (o instanceof FCQueue) {
			return ExpirableTxnRecord.allToGrpc(new ArrayList<>((FCQueue<ExpirableTxnRecord>) o)).toString();
		} else {
			return o.toString();
		}
	}

	public static JKey asFcKeyUnchecked(Key key) {
		try {
			return JKey.mapKey(key);
		} catch (Exception impossible) {
			throw new IllegalArgumentException("Key " + key + " should have been decodable!", impossible);
		}
	}

	public static Optional<JKey> asUsableFcKey(Key key) {
		try {
			var fcKey = JKey.mapKey(key);
			if (!fcKey.isValid()) {
				return Optional.empty();
			}
			return Optional.of(fcKey);
		} catch (Exception ignore) {
			return Optional.empty();
		}
	}

	public static Key asKeyUnchecked(JKey fcKey) {
		try {
			return mapJKey(fcKey);
		} catch (Exception impossible) {
			return Key.getDefaultInstance();
		}
	}

	public static Timestamp asTimestamp(Instant when) {
		return Timestamp.newBuilder()
				.setSeconds(when.getEpochSecond())
				.setNanos(when.getNano())
				.build();
	}

	public static Optional<QueryHeader> activeHeaderFrom(Query query) {
		switch (query.getQueryCase()) {
			case TOKENGETINFO:
				return Optional.of(query.getTokenGetInfo().getHeader());
			case CONSENSUSGETTOPICINFO:
				return Optional.of(query.getConsensusGetTopicInfo().getHeader());
			case GETBYSOLIDITYID:
				return Optional.of(query.getGetBySolidityID().getHeader());
			case CONTRACTCALLLOCAL:
				return Optional.of(query.getContractCallLocal().getHeader());
			case CONTRACTGETINFO:
				return Optional.of(query.getContractGetInfo().getHeader());
			case CONTRACTGETBYTECODE:
				return Optional.of(query.getContractGetBytecode().getHeader());
			case CONTRACTGETRECORDS:
				return Optional.of(query.getContractGetRecords().getHeader());
			case CRYPTOGETACCOUNTBALANCE:
				return Optional.of(query.getCryptogetAccountBalance().getHeader());
			case CRYPTOGETACCOUNTRECORDS:
				return Optional.of(query.getCryptoGetAccountRecords().getHeader());
			case CRYPTOGETINFO:
				return Optional.of(query.getCryptoGetInfo().getHeader());
			case CRYPTOGETLIVEHASH:
				return Optional.of(query.getCryptoGetLiveHash().getHeader());
			case CRYPTOGETPROXYSTAKERS:
				return Optional.of(query.getCryptoGetProxyStakers().getHeader());
			case FILEGETCONTENTS:
				return Optional.of(query.getFileGetContents().getHeader());
			case FILEGETINFO:
				return Optional.of(query.getFileGetInfo().getHeader());
			case TRANSACTIONGETRECEIPT:
				return Optional.of(query.getTransactionGetReceipt().getHeader());
			case TRANSACTIONGETRECORD:
				return Optional.of(query.getTransactionGetRecord().getHeader());
			case TRANSACTIONGETFASTRECORD:
				return Optional.of(query.getTransactionGetFastRecord().getHeader());
			case NETWORKGETVERSIONINFO:
				return Optional.of(query.getNetworkGetVersionInfo().getHeader());
			default:
				return Optional.empty();
		}
	}

	public static String getTxnStat(TransactionBody txn) {
		if (txn.hasCryptoCreateAccount()) {
			return CRYPTO_CREATE_METRIC;
		} else if (txn.hasCryptoUpdateAccount()) {
			return CRYPTO_UPDATE_METRIC;
		} else if (txn.hasCryptoTransfer()) {
			return CRYPTO_TRANSFER_METRIC;
		} else if (txn.hasCryptoDelete()) {
			return CRYPTO_DELETE_METRIC;
		} else if (txn.hasContractCreateInstance()) {
			return "createContract";
		} else if (txn.hasContractCall()) {
			return "contractCallMethod";
		} else if (txn.hasContractUpdateInstance()) {
			return "updateContract";
		} else if (txn.hasContractDeleteInstance()) {
			return "deleteContract";
		} else if (txn.hasCryptoAddLiveHash()) {
			return "addLiveHash";
		} else if (txn.hasCryptoDeleteLiveHash()) {
			return "deleteLiveHash";
		} else if (txn.hasFileCreate()) {
			return CREATE_FILE_METRIC;
		} else if (txn.hasFileAppend()) {
			return FILE_APPEND_METRIC;
		} else if (txn.hasFileUpdate()) {
			return UPDATE_FILE_METRIC;
		} else if (txn.hasFileDelete()) {
			return DELETE_FILE_METRIC;
		} else if (txn.hasSystemDelete()) {
			return "systemDelete";
		} else if (txn.hasSystemUndelete()) {
			return "systemUndelete";
		} else if (txn.hasFreeze()) {
			return "freeze";
		} else if (txn.hasConsensusCreateTopic()) {
			return CREATE_TOPIC_METRIC;
		} else if (txn.hasConsensusUpdateTopic()) {
			return UPDATE_TOPIC_METRIC;
		} else if (txn.hasConsensusDeleteTopic()) {
			return DELETE_TOPIC_METRIC;
		} else if (txn.hasConsensusSubmitMessage()) {
			return SUBMIT_MESSAGE_METRIC;
		} else if (txn.hasTokenCreation()) {
			return TOKEN_CREATE_METRIC;
		} else if (txn.hasTokenTransfers()) {
			return TOKEN_TRANSACT_METRIC;
		} else if (txn.hasTokenFreeze()) {
			return TOKEN_FREEZE_METRIC;
		} else if (txn.hasTokenUnfreeze()) {
			return TOKEN_UNFREEZE_METRIC;
		} else if (txn.hasTokenGrantKyc()) {
			return TOKEN_GRANT_KYC_METRIC;
		} else if (txn.hasTokenRevokeKyc()) {
			return TOKEN_REVOKE_KYC_METRIC;
		} else if (txn.hasTokenDeletion()) {
			return TOKEN_DELETE_METRIC;
		} else if (txn.hasTokenUpdate()) {
			return TOKEN_UPDATE_METRIC;
		} else if (txn.hasTokenMint()) {
			return TOKEN_MINT_METRIC;
		} else if (txn.hasTokenBurn()) {
			return TOKEN_BURN_METRIC;
		} else if (txn.hasTokenWipe()) {
			return TOKEN_WIPE_ACCOUNT_METRIC;
		} else if (txn.hasTokenAssociate()) {
			return TOKEN_ASSOCIATE_METRIC;
		} else if (txn.hasTokenDissociate()) {
			return TOKEN_DISSOCIATE_METRIC;
		} else {
			return "NotImplemented";
		}
	}

	public static HederaFunctionality functionOf(TransactionBody txn) throws UnknownHederaFunctionality {
		if (txn.hasSystemDelete()) {
			return SystemDelete;
		} else if (txn.hasSystemUndelete()) {
			return SystemUndelete;
		} else if (txn.hasContractCall()) {
			return ContractCall;
		} else if (txn.hasContractCreateInstance()) {
			return ContractCreate;
		} else if (txn.hasContractUpdateInstance()) {
			return ContractUpdate;
		} else if (txn.hasCryptoAddLiveHash()) {
			return CryptoAddLiveHash;
		} else if (txn.hasCryptoCreateAccount()) {
			return CryptoCreate;
		} else if (txn.hasCryptoDelete()) {
			return CryptoDelete;
		} else if (txn.hasCryptoDeleteLiveHash()) {
			return CryptoDeleteLiveHash;
		} else if (txn.hasCryptoTransfer()) {
			return CryptoTransfer;
		} else if (txn.hasCryptoUpdateAccount()) {
			return CryptoUpdate;
		} else if (txn.hasFileAppend()) {
			return FileAppend;
		} else if (txn.hasFileCreate()) {
			return FileCreate;
		} else if (txn.hasFileDelete()) {
			return FileDelete;
		} else if (txn.hasFileUpdate()) {
			return FileUpdate;
		} else if (txn.hasContractDeleteInstance()) {
			return ContractDelete;
		} else if (txn.hasFreeze()) {
			return Freeze;
		} else if (txn.hasConsensusCreateTopic()) {
			return ConsensusCreateTopic;
		} else if (txn.hasConsensusUpdateTopic()) {
			return ConsensusUpdateTopic;
		} else if (txn.hasConsensusDeleteTopic()) {
			return ConsensusDeleteTopic;
		} else if (txn.hasConsensusSubmitMessage()) {
			return ConsensusSubmitMessage;
		} else if (txn.hasTokenCreation()) {
			return TokenCreate;
		} else if (txn.hasTokenTransfers()) {
			return TokenTransact;
		} else if (txn.hasTokenFreeze()) {
			return TokenFreezeAccount;
		} else if (txn.hasTokenUnfreeze()) {
			return TokenUnfreezeAccount;
		} else if (txn.hasTokenGrantKyc()) {
			return TokenGrantKycToAccount;
		} else if (txn.hasTokenRevokeKyc()) {
			return TokenRevokeKycFromAccount;
		} else if (txn.hasTokenDeletion()) {
			return TokenDelete;
		} else if (txn.hasTokenUpdate()) {
			return TokenUpdate;
		} else if (txn.hasTokenMint()) {
			return TokenMint;
		} else if (txn.hasTokenBurn()) {
			return TokenBurn;
		} else if (txn.hasTokenWipe()) {
			return TokenAccountWipe;
		} else if (txn.hasTokenAssociate()) {
			return TokenAssociateToAccount;
		} else if (txn.hasTokenDissociate()) {
			return TokenDissociateFromAccount;
		} else if (txn.hasUncheckedSubmit()) {
			return UncheckedSubmit;
		} else {
			throw new UnknownHederaFunctionality();
		}
	}

	public static Optional<HederaFunctionality> functionalityOfQuery(Query query) {
		return Optional.ofNullable(queryFunctions.get(query.getQueryCase()));
	}

	public static String commonsBytesToHex(byte[] data) {
		return Hex.encodeHexString(data);
	}

	public static byte[] commonsHexToBytes(String literal) throws DecoderException {
		return Hex.decodeHex(literal);
	}

	public static String describe(JKey k) {
		if (k == null) {
			return "<N/A>";
		} else {
			Key readable = null;
			try {
				readable = mapJKey(k);
			} catch (Exception ignore) { }
			return String.valueOf(readable);
		}
	}

	public static Set<AccountID> getNodeAccounts(AddressBook addressBook) {
		return IntStream.range(0, addressBook.getSize())
				.mapToObj(addressBook::getAddress)
				.map(address -> accountParsedFromString(address.getMemo()))
				.collect(toSet());
	}

	public static Set<Long> getNodeAccountNums(AddressBook addressBook) {
		return getNodeAccounts(addressBook).stream().map(AccountID::getAccountNum).collect(toSet());
	}
}
