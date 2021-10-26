package com.hedera.services.state.merkle.internals;

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

import com.swirlds.common.crypto.Hash;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import com.swirlds.fcqueue.FCQueueElement;

import java.io.IOException;

public class FilePart implements FCQueueElement {
	private static final int CURRENT_VERSION = 1;
	private static final long CLASS_ID = 0xd1b1fc6b87447a02L;

	private Hash hash;
	private byte[] data;

	public FilePart() {
		/* RuntimeConstructable */
	}

	public byte[] getData() {
		return data;
	}

	public FilePart(byte[] data) {
		this.data = data;
	}

	@Override
	public FilePart copy() {
		return this;
	}

	@Override
	public void release() {
		/* No-op */
	}

	@Override
	public Hash getHash() {
		return hash;
	}

	@Override
	public void setHash(Hash hash) {
		this.hash = hash;
	}

	@Override
	public long getClassId() {
		return CLASS_ID;
	}

	@Override
	public void deserialize(SerializableDataInputStream in, int version) throws IOException {
		data = in.readByteArray(Integer.MAX_VALUE);
	}

	@Override
	public void serialize(SerializableDataOutputStream out) throws IOException {
		out.writeByteArray(data);
	}

	@Override
	public int getVersion() {
		return CURRENT_VERSION;
	}
}
