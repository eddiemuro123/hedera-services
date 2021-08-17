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

import static com.hedera.services.state.enums.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.FcCustomFee;
import com.hedera.services.state.submerkle.FixedFeeSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenSigningMetadataTest {
  @Test
  void classifiesRoyaltyWithFallback() {
    // setup:
    final var treasury = new EntityId(1, 2, 4);
    var royaltyFeeWithFallbackToken =
        new MerkleToken(Long.MAX_VALUE, 100, 1, "ZPHYR", "West Wind Art", false, true, treasury);
    royaltyFeeWithFallbackToken.setTokenType(NON_FUNGIBLE_UNIQUE);
    royaltyFeeWithFallbackToken.setFeeSchedule(
        List.of(FcCustomFee.royaltyFee(1, 2, new FixedFeeSpec(1, null), new EntityId(1, 2, 5))));

    // given:
    final var meta = TokenSigningMetadata.from(royaltyFeeWithFallbackToken);

    // expect:
    assertTrue(meta.hasRoyaltyWithFallback());
    assertSame(treasury, meta.treasury());
  }

  @Test
  void classifiesRoyaltyWithNoFallback() {
    // setup:
    final var treasury = new EntityId(1, 2, 4);
    var royaltyFeeNoFallbackToken =
        new MerkleToken(Long.MAX_VALUE, 100, 1, "ZPHYR", "West Wind Art", false, true, treasury);
    royaltyFeeNoFallbackToken.setTokenType(NON_FUNGIBLE_UNIQUE);
    royaltyFeeNoFallbackToken.setFeeSchedule(
        List.of(FcCustomFee.royaltyFee(1, 2, null, new EntityId(1, 2, 5))));

    // given:
    final var meta = TokenSigningMetadata.from(royaltyFeeNoFallbackToken);

    // expect:
    assertFalse(meta.hasRoyaltyWithFallback());
    assertSame(treasury, meta.treasury());
  }
}
