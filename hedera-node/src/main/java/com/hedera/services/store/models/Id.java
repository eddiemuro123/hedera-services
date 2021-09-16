package com.hedera.services.store.models;

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

import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.utils.EntityIdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.TokenID;
import org.hyperledger.besu.datatypes.Address;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Represents the id of a Hedera entity (account, topic, token, contract, file, or schedule).
 */
public class Id {
	public static final Id DEFAULT = new Id(0, 0, 0);
	public static final Comparator<Id> ID_COMPARATOR = Comparator
			.comparingLong(Id::getNum)
			.thenComparingLong(Id::getShard)
			.thenComparingLong(Id::getRealm);

	private final long shard;
	private final long realm;
	private final long num;

	public static final Id MISSING_ID = new Id(0, 0, 0);

	public Id(long shard, long realm, long num) {
		this.shard = shard;
		this.realm = realm;
		this.num = num;
	}

	public AccountID asGrpcAccount() {
		return AccountID.newBuilder()
				.setShardNum(shard)
				.setRealmNum(realm)
				.setAccountNum(num)
				.build();
	}

	public TokenID asGrpcToken() {
		return TokenID.newBuilder()
				.setShardNum(shard)
				.setRealmNum(realm)
				.setTokenNum(num)
				.build();
	}

	public ContractID asGrpcContract() {
		return ContractID.newBuilder()
				.setShardNum(shard)
				.setRealmNum(realm)
				.setContractNum(num)
				.build();
	}

	public static Id fromGrpcAccount(final AccountID id) {
		return new Id(id.getShardNum(), id.getRealmNum(), id.getAccountNum());
	}

	public static Id fromGrpcContract(final ContractID id) {
		return new Id(id.getShardNum(), id.getRealmNum(), id.getContractNum());
	}

	public static Id fromGrpcToken(final TokenID id) {
		return new Id(id.getShardNum(), id.getRealmNum(), id.getTokenNum());
	}

	public static Id fromEvmAddress(final Address address) {
		final var addressBytes = address.toArray();
		final int shardId = Ints.fromByteArray(Arrays.copyOfRange(addressBytes, 0, 4));
		final long realm = Longs.fromByteArray(Arrays.copyOfRange(addressBytes, 4, 12));
		final long num = Longs.fromByteArray(Arrays.copyOfRange(addressBytes, 12, 20));
		return new Id(shardId, realm, num);
	}

	public long getShard() {
		return shard;
	}

	public long getRealm() {
		return realm;
	}

	public long getNum() {
		return num;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this) {
			return true;
		}
		if (obj == null || !Id.class.equals(obj.getClass())) {
			return false;
		}
		final Id that = (Id) obj;

		return this.shard == that.shard && this.realm == that.realm && this.num == that.num;
	}

	@Override
	public int hashCode() {
		int result = Long.hashCode(shard);
		result = 31 * result + Long.hashCode(realm);
		return 31 * result + Long.hashCode(num);
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(Id.class)
				.add("shard", shard)
				.add("realm", realm)
				.add("num", num)
				.toString();
	}

	public EntityId asEntityId() {
		return new EntityId(shard, realm, num);
	}

	public MerkleEntityId asMerkle() {
		return new MerkleEntityId(shard, realm, num);
	}

	/**
	 * Returns the EVM representation of the Account
	 * @return {@link Address} evm representation
	 */
	public Address asEvmAddress() {
		return Address.fromHexString(EntityIdUtils.asSolidityAddressHex(this));
	}
}
