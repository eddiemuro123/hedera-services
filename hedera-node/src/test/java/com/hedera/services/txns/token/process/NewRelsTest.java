package com.hedera.services.txns.token.process;

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

import com.hedera.services.state.submerkle.FcCustomFee;
import com.hedera.services.store.models.Account;
import com.hedera.services.store.models.Id;
import com.hedera.services.store.models.Token;
import com.hedera.services.store.models.TokenRelationship;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;


@ExtendWith(MockitoExtension.class)
class NewRelsTest {
	private static final int MAX_PER_ACCOUNT = 1_000;
	private static final Id treasuryId = new Id(0, 0, 777);
	private static final Id collectorId = new Id(0, 0, 888);

	@Mock
	private Token provisionalToken;
	@Mock
	private Account treasury;
	@Mock
	private Account collector;
	@Mock
	private FcCustomFee feeNoCollectorAssociationRequired;
	@Mock
	private FcCustomFee feeCollectorAssociationRequired;
	@Mock
	private FcCustomFee feeSameCollectorAssociationRequired;
	@Mock
	private TokenRelationship treasuryRel;
	@Mock
	private TokenRelationship collectorRel;

	@Test
	void associatesAsExpected() {
		given(treasury.getId()).willReturn(treasuryId);
		given(collector.getId()).willReturn(collectorId);
		given(provisionalToken.getTreasury()).willReturn(treasury);
		given(feeCollectorAssociationRequired.requiresCollectorAutoAssociation()).willReturn(true);
		given(feeCollectorAssociationRequired.getValidatedCollector()).willReturn(collector);
		given(feeSameCollectorAssociationRequired.requiresCollectorAutoAssociation()).willReturn(true);
		given(feeSameCollectorAssociationRequired.getValidatedCollector()).willReturn(collector);
		given(provisionalToken.getCustomFees()).willReturn(List.of(
				feeCollectorAssociationRequired,
				feeNoCollectorAssociationRequired,
				feeSameCollectorAssociationRequired));
		given(provisionalToken.newEnabledRelationship(treasury)).willReturn(treasuryRel);
		given(provisionalToken.newEnabledRelationship(collector)).willReturn(collectorRel);

		final var ans = NewRels.listFrom(provisionalToken, MAX_PER_ACCOUNT);

		assertEquals(List.of(treasuryRel, collectorRel), ans);
		verify(treasury).associateWith(List.of(provisionalToken), MAX_PER_ACCOUNT, false);
		verify(collector).associateWith(List.of(provisionalToken), MAX_PER_ACCOUNT, false);
		verify(provisionalToken, times(1)).newEnabledRelationship(collector);
	}
}
