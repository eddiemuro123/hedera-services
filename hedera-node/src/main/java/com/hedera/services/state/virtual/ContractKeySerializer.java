package com.hedera.services.state.virtual;

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

import com.swirlds.common.io.SerializableDataOutputStream;
import com.swirlds.jasperdb.files.DataFileCommon;
import com.swirlds.jasperdb.files.hashmap.KeySerializer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * KeySerializer for ContractKeys
 */
public class ContractKeySerializer implements KeySerializer<ContractKey> {
	static final long DATA_VERSION = 1;
	/**
	 * Get if the number of bytes a data item takes when serialized is variable or fixed
	 *
	 * @return true if getSerializedSize() == DataFileCommon.VARIABLE_DATA_SIZE
	 */
	@Override
	public boolean isVariableSize() {
		return true;
	}

	/**
	 * Get the number of bytes a data item takes when serialized
	 *
	 * @return Either a number of bytes or DataFileCommon.VARIABLE_DATA_SIZE if size is variable
	 */
	@Override
	public int getSerializedSize() {
		return DataFileCommon.VARIABLE_DATA_SIZE;
	}

	/**
	 * For variable sized data get the typical  number of bytes a data item takes when serialized
	 *
	 * @return Either for fixed size same as getSerializedSize() or an estimated typical size for data items
	 */
	@Override
	public int getTypicalSerializedSize() {
		return ContractKey.ESTIMATED_AVERAGE_SIZE;
	}

	/**
	 * Get the current data item serialization version
	 */
	@Override
	public long getCurrentDataVersion() {
		return DATA_VERSION;
	}

	/**
	 * Deserialize a data item from a byte buffer, that was written with given data version
	 *
	 * @param buffer
	 * 		The buffer to read from containing the data item including its header
	 * @param dataVersion
	 * 		The serialization version the data item was written with
	 * @return Deserialized data item
	 */
	@Override
	public ContractKey deserialize(ByteBuffer buffer, long dataVersion) throws IOException {
		Objects.requireNonNull(buffer);
		ContractKey contractKey = new ContractKey();
		contractKey.deserialize(buffer, (int) dataVersion);
		return contractKey;
	}

	/**
	 * Serialize a data item including header to the output stream returning the size of the data written
	 *
	 * @param data
	 * 		The data item to serialize
	 * @param outputStream
	 * 		Output stream to write to
	 */
	@Override
	public int serialize(ContractKey data, SerializableDataOutputStream outputStream) throws IOException {
		Objects.requireNonNull(data);
		Objects.requireNonNull(outputStream);
		return data.serializeReturningByteWritten(outputStream);
	}

	/**
	 * Deserialize key size from the given byte buffer
	 *
	 * @param buffer
	 * 		Buffer to read from
	 * @return The number of bytes used to store the key, including for storing the key size if needed.
	 */
	@Override
	public int deserializeKeySize(ByteBuffer buffer) {
		return ContractKey.readKeySize(buffer);
	}
}
