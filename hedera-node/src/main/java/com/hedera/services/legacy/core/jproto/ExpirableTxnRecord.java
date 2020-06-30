package com.hedera.services.legacy.core.jproto;

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

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.state.serdes.DomainSerdes;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.HbarAdjustments;
import com.hedera.services.state.submerkle.RichInstant;
import com.hedera.services.state.submerkle.SolidityFnResult;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionReceipt;
import com.hederahashgraph.api.proto.java.TransactionRecord;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.swirlds.common.crypto.Hash;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import com.swirlds.common.io.SerializedObjectProvider;
import com.swirlds.fcqueue.FCQueueElement;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.encoders.Hex;

import static java.util.stream.Collectors.toList;

public class ExpirableTxnRecord implements FCQueueElement<ExpirableTxnRecord> {
	private static final Logger log = LogManager.getLogger(ExpirableTxnRecord.class);

	private static final byte[] MISSING_TXN_HASH = new byte[0];

	static final int MERKLE_VERSION = 1;
	static final int MAX_MEMO_BYTES = 32 * 1_024;
	static final int MAX_TXN_HASH_BYTES = 1_024;
	static final long RUNTIME_CONSTRUCTABLE_ID = 0x8b9ede7ca8d8db93L;

	@Deprecated
	public static final Provider LEGACY_PROVIDER = new Provider();

	static DomainSerdes serdes = new DomainSerdes();
	static TxnId.Provider legacyTxnIdProvider = TxnId.LEGACY_PROVIDER;
	static TxnReceipt.Provider legacyReceiptProvider = TxnReceipt.LEGACY_PROVIDER;
	static RichInstant.Provider legacyInstantProvider = RichInstant.LEGACY_PROVIDER;
	static HbarAdjustments.Provider legacyAdjustmentsProvider = HbarAdjustments.LEGACY_PROVIDER;
	static SolidityFnResult.Provider legacyFnResultProvider = SolidityFnResult.LEGACY_PROVIDER;

	private long fee;
	private long expiry;
	private Hash hash;
	private TxnId txnId;
	private byte[] txnHash = MISSING_TXN_HASH;
	private String memo;
	private RichInstant consensusTimestamp;
	private HbarAdjustments hbarAdjustments;
	private SolidityFnResult contractCallResult;
	private SolidityFnResult contractCreateResult;
	private TxnReceipt receipt;

	@Deprecated
	public static class Provider implements SerializedObjectProvider<ExpirableTxnRecord> {
		@Override
		public ExpirableTxnRecord deserialize(DataInputStream in) throws IOException {
			var record = new ExpirableTxnRecord();

			in.readLong();
			in.readLong();

			if (in.readBoolean()) {
				record.receipt = legacyReceiptProvider.deserialize(in);
			}
			int numHashBytes = in.readInt();
			if (numHashBytes > 0) {
				record.txnHash = new byte[numHashBytes];
				in.readFully(record.txnHash);
			}
			if (in.readBoolean()) {
				record.txnId = legacyTxnIdProvider.deserialize(in);
			}
			if (in.readBoolean()) {
				record.consensusTimestamp = legacyInstantProvider.deserialize(in);
			}
			int numMemoBytes = in.readInt();
			if (numMemoBytes > 0) {
				byte[] memoBytes = new byte[numMemoBytes];
				in.readFully(memoBytes);
				record.memo = new String(memoBytes);
			}
			record.fee = in.readLong();
			if (in.readBoolean()) {
				record.hbarAdjustments = legacyAdjustmentsProvider.deserialize(in);
			}
			if (in.readBoolean()) {
				record.contractCallResult = legacyFnResultProvider.deserialize(in);
			}
			if (in.readBoolean()) {
				record.contractCreateResult = legacyFnResultProvider.deserialize(in);
			}
			record.expiry = in.readLong();

			return record;
		}
	}

	public ExpirableTxnRecord() { }

	public ExpirableTxnRecord(
			TxnReceipt receipt,
			byte[] txnHash,
			TxnId txnId,
			RichInstant consensusTimestamp,
			String memo,
			long fee,
			HbarAdjustments transferList,
			SolidityFnResult contractCallResult,
			SolidityFnResult createResult
	) {
		this.receipt = receipt;
		this.txnHash = txnHash;
		this.txnId = txnId;
		this.consensusTimestamp = consensusTimestamp;
		this.memo = memo;
		this.fee = fee;
		this.hbarAdjustments = transferList;
		this.contractCallResult = contractCallResult;
		this.contractCreateResult = createResult;
	}

	/* --- Object --- */

	@Override
	public String toString() {
		var helper = MoreObjects.toStringHelper(this)
				.add("receipt", receipt)
				.add("txnHash", Hex.toHexString(txnHash))
				.add("txnId", txnId)
				.add("consensusTimestamp", consensusTimestamp)
				.add("expiry", expiry);
		if (memo != null)	 {
			helper.add("memo", memo);
		}
		if (contractCreateResult != null) {
			helper.add("contractCreation", contractCreateResult);
		}
		if (contractCallResult != null) {
			helper.add("contractCall", contractCallResult);
		}
		if (hbarAdjustments != null) {
			helper.add("adjustments", hbarAdjustments);
		}
		return helper.toString();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || ExpirableTxnRecord.class != o.getClass()) {
			return false;
		}
		var that = (ExpirableTxnRecord) o;
		return fee == that.fee &&
				receipt.equals(that.receipt) &&
				Arrays.equals(txnHash, that.txnHash) &&
				txnId.equals(that.txnId) &&
				Objects.equals(consensusTimestamp, that.consensusTimestamp) &&
				Objects.equals(memo, that.memo) &&
				Objects.equals(contractCallResult, that.contractCallResult) &&
				Objects.equals(contractCreateResult, that.contractCreateResult) &&
				Objects.equals(hbarAdjustments, that.hbarAdjustments) &&
				Objects.equals(expiry, that.expiry);
	}

	@Override
	public int hashCode() {
		var result = Objects.hash(
				receipt,
				txnId,
				consensusTimestamp,
				memo,
				fee,
				contractCallResult,
				contractCreateResult,
				hbarAdjustments,
				expiry);
		return result * 31 + Arrays.hashCode(txnHash);
	}

	/* --- SelfSerializable --- */

	@Override
	public long getClassId() {
		return RUNTIME_CONSTRUCTABLE_ID;
	}

	@Override
	public int getVersion() {
		return MERKLE_VERSION;
	}

	@Override
	public void serialize(final SerializableDataOutputStream out) throws IOException {
		serdes.writeNullableSerializable(receipt, out);

		out.writeByteArray(txnHash);

		serdes.writeNullableSerializable(txnId, out);
		serdes.writeNullableInstant(consensusTimestamp, out);
		serdes.writeNullableString(memo, out);

		out.writeLong(this.fee);

		serdes.writeNullableSerializable(hbarAdjustments, out);
		serdes.writeNullableSerializable(contractCallResult, out);
		serdes.writeNullableSerializable(contractCreateResult, out);

		out.writeLong(expiry);
	}

	@Override
	public void deserialize(SerializableDataInputStream in, int version) throws IOException {
		receipt = serdes.readNullableSerializable(in);
		txnHash = in.readByteArray(MAX_TXN_HASH_BYTES);
		txnId = serdes.readNullableSerializable(in);
		consensusTimestamp = serdes.readNullableInstant(in);
		memo = serdes.readNullableString(in, MAX_MEMO_BYTES);
		fee = in.readLong();
		hbarAdjustments = serdes.readNullableSerializable(in);
		contractCallResult = serdes.readNullableSerializable(in);
		contractCreateResult = serdes.readNullableSerializable(in);
		expiry = in.readLong();
	}

	@Override
	public Hash getHash() {
		return this.hash;
	}

	@Override
	public void setHash(Hash hash) {
		this.hash = hash;
	}

	/* --- Object --- */

	public TxnReceipt getReceipt() {
		return receipt;
	}

	public byte[] getTxnHash() {
		return txnHash;
	}

	public TxnId getTxnId() {
		return txnId;
	}

	public RichInstant getConsensusTimestamp() {
		return consensusTimestamp;
	}

	public String getMemo() {
		return memo;
	}

	public long getFee() {
		return fee;
	}

	public SolidityFnResult getContractCallResult() {
		return contractCallResult;
	}

	public SolidityFnResult getContractCreateResult() {
		return contractCreateResult;
	}

	public HbarAdjustments getHbarAdjustments() {
		return hbarAdjustments;
	}

	public long getExpiry() {
		return expiry;
	}

	public void setExpiry(long expiry) {
		this.expiry = expiry;
	}


	/* --- FastCopyable --- */

	@Override
	public boolean isImmutable() {
		return true;
	}

	@Override
	public ExpirableTxnRecord copy() {
		return this;
	}

	@Override
	public void delete() { }

	@Override
	@Deprecated
	public void copyTo(SerializableDataOutputStream out) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void copyToExtra(SerializableDataOutputStream out) {
		throw new UnsupportedOperationException();
	}

	@Override
	@Deprecated
	public void copyFrom(SerializableDataInputStream in) {
		throw new UnsupportedOperationException();
	}

	@Override
	@Deprecated
	public void copyFromExtra(SerializableDataInputStream in) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void diffCopyTo(SerializableDataOutputStream out, SerializableDataInputStream in) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void diffCopyFrom(SerializableDataOutputStream out, SerializableDataInputStream in) {
		throw new UnsupportedOperationException();
	}

	/* --- Helpers --- */

	public static ExpirableTxnRecord fromGprc(TransactionRecord record) {
		var txnReceipt = TxnReceipt.fromGrpc(record.getReceipt());
		var txnId = TxnId.fromGrpc(record.getTransactionID());

		HbarAdjustments hbarAdjustments = null;
		if (record.hasTransferList()) {
			hbarAdjustments = HbarAdjustments.fromGrpc(record.getTransferList());
		}

		SolidityFnResult callResult = null;
		if (record.hasContractCallResult()) {
			callResult = SolidityFnResult.fromGrpc(record.getContractCallResult());
		}

		SolidityFnResult createResult = null;
		if (record.hasContractCreateResult()) {
			createResult = SolidityFnResult.fromGrpc(record.getContractCreateResult());
		}

		return new ExpirableTxnRecord(
				txnReceipt,
				record.getTransactionHash().toByteArray(),
				txnId,
				RichInstant.fromGrpc(record.getConsensusTimestamp()),
				record.getMemo(),
				record.getTransactionFee(),
				hbarAdjustments,
				callResult,
				createResult);
	}

	public static List<TransactionRecord> allToGrpc(List<ExpirableTxnRecord> records) {
		return records.stream()
				.map(ExpirableTxnRecord::toGrpc)
				.collect(toList());
	}

	public static TransactionRecord toGrpc(ExpirableTxnRecord expirableTxnRecord) {
		TransactionRecord.Builder grpc = TransactionRecord.newBuilder();

		TransactionReceipt txReceipt = TxnReceipt.convert(expirableTxnRecord.getReceipt());
		if (expirableTxnRecord.getTxnId() != null) {
			EntityId payer = expirableTxnRecord.getTxnId().getPayerAccount();
			RichInstant timestamp = expirableTxnRecord.getTxnId().getValidStart();
			if ((payer != null) || (timestamp != null)) {
				grpc.setTransactionID(expirableTxnRecord.getTxnId().toGrpc());
			}
		}

		Timestamp timestamp = Timestamp.newBuilder().build();
		if (expirableTxnRecord.getConsensusTimestamp() != null) {
			timestamp = expirableTxnRecord.getConsensusTimestamp().toGrpc();
		}
		grpc.setConsensusTimestamp(timestamp)
				.setTransactionFee(expirableTxnRecord.getFee())
				.setReceipt(txReceipt);

		if (expirableTxnRecord.getMemo() != null) {
			grpc.setMemo(expirableTxnRecord.getMemo());
		}
		if (expirableTxnRecord.getTxnHash().length > 0) {
			grpc.setTransactionHash(ByteString.copyFrom(expirableTxnRecord.getTxnHash()));
		}
		if (expirableTxnRecord.getHbarAdjustments() != null) {
			grpc.setTransferList(expirableTxnRecord.getHbarAdjustments().toGrpc());
		}
		if (expirableTxnRecord.getContractCallResult() != null) {
			var contractCallResult = expirableTxnRecord.getContractCallResult().toGrpc();
			grpc.setContractCallResult(contractCallResult);
		}
		if (expirableTxnRecord.getContractCreateResult() != null) {
			ContractFunctionResult contractCreateResult = expirableTxnRecord.getContractCreateResult().toGrpc();
			grpc.setContractCreateResult(contractCreateResult);
		}

		return grpc.build();
	}
}
