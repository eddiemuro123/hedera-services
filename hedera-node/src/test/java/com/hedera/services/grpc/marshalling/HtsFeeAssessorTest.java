package com.hedera.services.grpc.marshalling;

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

import com.hedera.services.ledger.BalanceChange;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.FcAssessedCustomFee;
import com.hedera.services.state.submerkle.FcCustomFee;
import com.hedera.services.store.models.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE_FOR_CUSTOM_FEE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HtsFeeAssessorTest {
	private final List<FcAssessedCustomFee> accumulator = new ArrayList<>();

	@Mock
	private BalanceChange payerChange;
	@Mock
	private BalanceChange collectorChange;
	@Mock
	private BalanceChangeManager balanceChangeManager;

	private HtsFeeAssessor subject = new HtsFeeAssessor();

	@Test
	void updatesExistingChangesIfPresent() {
		// setup:
		final var expectedAssess = new FcAssessedCustomFee(htsFeeCollector, feeDenom, amountOfHtsFee);
		// and:
		final var expectedPayerChange = BalanceChange.tokenAdjust(payer, denom, -amountOfHtsFee);
		expectedPayerChange.setCodeForInsufficientBalance(INSUFFICIENT_PAYER_BALANCE_FOR_CUSTOM_FEE);

		given(balanceChangeManager.changeFor(feeCollector, denom)).willReturn(collectorChange);

		// when:
		subject.assess(payer, htsFee, balanceChangeManager, accumulator);

		// then:
		verify(collectorChange).adjustUnits(+amountOfHtsFee);
		verify(balanceChangeManager).includeChange(expectedPayerChange);
		// and:
		assertEquals(1, accumulator.size());
		assertEquals(expectedAssess, accumulator.get(0));
	}

	@Test
	void addsNewChangesIfNotPresent() {
		// setup:
		final var expectedAssess = new FcAssessedCustomFee(htsFeeCollector, feeDenom, amountOfHtsFee);

		// given:
		final var expectedPayerChange = BalanceChange.tokenAdjust(payer, denom, -amountOfHtsFee);
		expectedPayerChange.setCodeForInsufficientBalance(INSUFFICIENT_PAYER_BALANCE_FOR_CUSTOM_FEE);

		// when:
		subject.assess(payer, htsFee, balanceChangeManager, accumulator);

		// then:
		verify(balanceChangeManager).includeChange(expectedPayerChange);
		verify(balanceChangeManager).includeChange(BalanceChange.tokenAdjust(feeCollector, denom, +amountOfHtsFee));
		// and:
		assertEquals(1, accumulator.size());
		assertEquals(expectedAssess, accumulator.get(0));
	}

	private final long amountOfHtsFee = 100_000L;
	private final Id payer = new Id(0, 1, 2);
	private final Id denom = new Id(6, 6, 6);
	private final Id feeCollector = new Id(1, 2, 3);
	private final EntityId feeDenom = new EntityId(6, 6, 6);
	private final EntityId htsFeeCollector = feeCollector.asEntityId();
	private final FcCustomFee htsFee = FcCustomFee.fixedFee(amountOfHtsFee, feeDenom, htsFeeCollector);
}
