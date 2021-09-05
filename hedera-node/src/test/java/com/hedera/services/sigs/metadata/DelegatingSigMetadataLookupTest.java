package com.hedera.services.sigs.metadata;

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

import com.hedera.services.legacy.core.jproto.JEd25519Key;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.sigs.metadata.lookups.SafeLookupResult;
import com.hedera.services.sigs.order.KeyOrderingFailure;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.test.utils.IdUtils;
import com.hedera.test.utils.TxnUtils;
import com.hederahashgraph.api.proto.java.TokenID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;

class DelegatingSigMetadataLookupTest {
	private JKey freezeKey;
	private String symbol = "NotAnHbar";
	private String tokenName = "TokenName";
	private int decimals = 2;
	private long totalSupply = 1_000_000;
	private boolean freezeDefault = true;
	private boolean accountsKycGrantedByDefault = true;
	private EntityId treasury = new EntityId(1,2, 3);
	private TokenID id = IdUtils.asToken("1.2.666");

	private MerkleToken token;
	private TokenStore tokenStore;

	private Function<TokenID, SafeLookupResult<TokenSigningMetadata>> subject;

	@BeforeEach
	void setup() {
		freezeKey = new JEd25519Key("not-a-real-freeze-key".getBytes());

		token = TxnUtils.typicalToken(Long.MAX_VALUE, totalSupply, decimals, symbol, tokenName,  treasury);
		token.setAccountsFrozenByDefault(freezeDefault);
		token.setAccountsKycGrantedByDefault(accountsKycGrantedByDefault);

		tokenStore = mock(TokenStore.class);

		subject = SigMetadataLookup.REF_LOOKUP_FACTORY.apply(tokenStore);
	}

	@Test
	void returnsExpectedFailIfExplicitlyMissing() {
		given(tokenStore.resolve(id)).willReturn(TokenID.newBuilder()
				.setShardNum(0L)
				.setRealmNum(0L)
				.setTokenNum(0L)
				.build());

		// when:
		var result = subject.apply(id);

		// then:
		assertEquals(KeyOrderingFailure.MISSING_TOKEN, result.failureIfAny());
	}

	@Test
	void returnsExpectedFailIfMissing() {
		given(tokenStore.resolve(id)).willReturn(TokenStore.MISSING_TOKEN);

		// when:
		var result = subject.apply(id);

		// then:
		assertEquals(KeyOrderingFailure.MISSING_TOKEN, result.failureIfAny());
	}

	@Test
	void returnsExpectedMetaIfPresent() {
		// setup:
		token.setFreezeKey(freezeKey);
		var expected = TokenSigningMetadata.from(token);

		given(tokenStore.resolve(id)).willReturn(id);
		given(tokenStore.get(id)).willReturn(token);

		// when:
		var result = subject.apply(id);

		// then:
		assertEquals(KeyOrderingFailure.NONE, result.failureIfAny());
		// and:
		assertEquals(expected.adminKey(), result.metadata().adminKey());
		assertEquals(expected.optionalFreezeKey(), result.metadata().optionalFreezeKey());
	}
}
