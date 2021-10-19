package com.hedera.services.contracts.persistence;

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

import com.hedera.services.contracts.annotations.StorageSource;
import com.hedera.services.state.virtual.ContractKey;
import com.hedera.services.state.virtual.ContractValue;
import com.hedera.services.store.contracts.DWUtil;
import com.swirlds.virtualmap.VirtualMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ethereum.datasource.Source;
import org.ethereum.datasource.StoragePersistence;
import org.ethereum.vm.DataWord;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.function.Supplier;

import static com.hedera.services.utils.EntityIdUtils.contractParsedFromSolidityAddress;

@Singleton
public class BlobStoragePersistence implements StoragePersistence {
	private static final Logger log = LogManager.getLogger(BlobStoragePersistence.class);

	private final Map<byte[], byte[]> storage;
	private final Supplier<VirtualMap<ContractKey, ContractValue>> contractStorage;

	@Inject
	public BlobStoragePersistence(
			@StorageSource Map<byte[], byte[]> storage,
			Supplier<VirtualMap<ContractKey, ContractValue>> contractStorage
	) {
		this.storage = storage;
		this.contractStorage = contractStorage;
	}

	@Override
	public boolean storageExist(byte[] address) {
		return storage.containsKey(address);
	}

	@Override
	public void persist(byte[] address, byte[] cache, long ignoredExpiry, long ignoredNow) {
		storage.put(address, cache);
	}

	@Override
	public byte[] get(byte[] address) {
		return storage.get(address);
	}

	@Override
	public Source<DataWord, DataWord> scopedStorageFor(byte[] address) {
		final var contractNum = contractParsedFromSolidityAddress(address).getContractNum();
		return new ScopedSource(contractNum);
	}

	private final class ScopedSource implements Source<DataWord, DataWord> {
		private final long contractNum;

		private ScopedSource(long contractNum) {
			this.contractNum = contractNum;
		}

		@Override
		public void put(DataWord key, DataWord value) {
			final var curContractStorage = contractStorage.get();
			final var storageKey = at(key);
			if (curContractStorage.containsKey(storageKey)) {
				final var mutableLeaf = curContractStorage.getForModify(storageKey);
				mutableLeaf.setValue(value.getData());
			} else {
				curContractStorage.put(storageKey, leafWith(value));
			}
		}

		@Override
		public DataWord get(DataWord key) {
			final var leaf = contractStorage.get().get(at(key));
			return leaf == null ? null : DataWord.of(leaf.getValue());
		}

		@Override
		public void delete(DataWord key) {
			contractStorage.get().remove(at(key));
		}

		@Override
		public boolean flush() {
			return false;
		}

		private ContractKey at(DataWord word) {
			return new ContractKey(contractNum, DWUtil.asPackedInts(word.getData()));
		}

		private ContractValue leafWith(DataWord word) {
			return new ContractValue(word.getData());
		}
	}
}
