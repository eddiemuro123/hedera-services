package com.hedera.services.store.contracts;

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

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.ethereum.vm.DataWord;

/**
 * Util class for converting {@link UInt256} variables to {@link DataWord} and vice-versa. Temporary solution while the
 * Legacy Smart Contract State is used
 */
public final class DWUtil {

	private DWUtil() {
		throw new UnsupportedOperationException("Utility Class");
	}

	/**
	 * Converts {@link UInt256} value to {@link DataWord}
 	 * @param uInt256 the value to convert
	 * @return the converted {@link DataWord} value
	 */
	public static DataWord fromUInt256(UInt256 uInt256) {
		return DataWord.of(uInt256.toArray());
	}

	/**
	 * Converts {@link DataWord} value to {@link UInt256}
	 * @param dataWord the value to convert
	 * @return the converted {@link UInt256} value
	 */
	public static UInt256 fromDataWord(DataWord dataWord) {
		return UInt256.fromBytes(Bytes32.wrap(dataWord.getData()));
	}
}