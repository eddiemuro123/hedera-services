package com.hedera.services.utils;

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

import com.hedera.services.state.enums.TokenSupplyType;
import com.hedera.services.state.enums.TokenType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenTypesMapperTest {
	@Test
	void grpcTokenTypeToModelType() {
		assertEquals(TokenType.FUNGIBLE_COMMON,
				TokenTypesMapper.grpcTokenTypeToModelType(com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON));
		assertEquals(TokenType.NON_FUNGIBLE_UNIQUE,
				TokenTypesMapper.grpcTokenTypeToModelType(
						com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE));
	}

	@Test
	void grpcTokenSupplyTypeToModelSupplyType() {
		assertEquals(TokenSupplyType.FINITE,
				TokenTypesMapper.grpcTokenSupplyTypeToModelSupplyType(
						com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE));

		assertEquals(TokenSupplyType.INFINITE,
				TokenTypesMapper.grpcTokenSupplyTypeToModelSupplyType(
						com.hederahashgraph.api.proto.java.TokenSupplyType.INFINITE));

		/* ensure default is infinite */
		assertEquals(TokenSupplyType.INFINITE,
				TokenTypesMapper.grpcTokenSupplyTypeToModelSupplyType(
						com.hederahashgraph.api.proto.java.TokenSupplyType.UNRECOGNIZED));
	}
}
