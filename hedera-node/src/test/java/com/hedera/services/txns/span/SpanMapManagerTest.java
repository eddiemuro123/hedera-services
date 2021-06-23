package com.hedera.services.txns.span;

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

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.grpc.marshalling.ImpliedTransfers;
import com.hedera.services.grpc.marshalling.ImpliedTransfersMarshal;
import com.hedera.services.state.submerkle.AssessedCustomFee;
import com.hedera.services.state.submerkle.CustomFee;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.txns.customfees.CustomFeeSchedules;
import com.hedera.services.utils.TxnAccessor;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SpanMapManagerTest {
	private final int maxHbarAdjusts = 1;
	private final int maxTokenAdjusts = 2;
	private final TransactionBody pretendXferTxn = TransactionBody.getDefaultInstance();
	private final ImpliedTransfers someImpliedXfers = ImpliedTransfers.invalid(
			maxHbarAdjusts, maxTokenAdjusts, ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS);
	private final ImpliedTransfers someOtherImpliedXfers = ImpliedTransfers.invalid(
			maxHbarAdjusts, maxTokenAdjusts + 1, ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS);

	private final EntityId customFeeToken = new EntityId(0, 0, 123);
	private final EntityId customFeeCollector = new EntityId(0, 0, 124);
	final List<Pair<EntityId, List<CustomFee>>> entityCustomFees = List.of(
			Pair.of(customFeeToken, new ArrayList<>()));
	final List<Pair<EntityId, List<CustomFee>>> newCustomFeeChanges = List.of(
			Pair.of(customFeeToken, List.of(CustomFee.fixedFee(10L, customFeeToken, customFeeCollector))));
	private final List<AssessedCustomFee> assessedCustomFees = List.of(
			new AssessedCustomFee(customFeeCollector, customFeeToken, 123L));
	private final ImpliedTransfers validImpliedTransfers = ImpliedTransfers.valid(
			maxHbarAdjusts, maxTokenAdjusts, new ArrayList<>(), entityCustomFees, assessedCustomFees);
	private final ImpliedTransfers feeChangedImpliedTransfers = ImpliedTransfers.valid(
			maxHbarAdjusts, maxTokenAdjusts + 1, new ArrayList<>(), newCustomFeeChanges, assessedCustomFees);

	private final ExpandHandleSpanMapAccessor spanMapAccessor = new ExpandHandleSpanMapAccessor();

	private Map<String, Object> span = new HashMap<>();

	@Mock
	private TxnAccessor accessor;
	@Mock
	private ImpliedTransfersMarshal impliedTransfersMarshal;
	@Mock
	private GlobalDynamicProperties dynamicProperties;

	@Mock
	private CustomFeeSchedules customFeeSchedules;

	private SpanMapManager subject;

	@BeforeEach
	void setUp() {
		subject = new SpanMapManager(impliedTransfersMarshal, dynamicProperties, customFeeSchedules);
	}

	@Test
	void expandsImpliedTransfersForCryptoTransfer() {
		given(accessor.getTxn()).willReturn(pretendXferTxn);
		given(accessor.getSpanMap()).willReturn(span);
		given(accessor.getFunction()).willReturn(CryptoTransfer);
		given(impliedTransfersMarshal.unmarshalFromGrpc(pretendXferTxn.getCryptoTransfer(), accessor.getPayer()))
				.willReturn(someImpliedXfers);

		// when:
		subject.expandSpan(accessor);

		// then:
		assertSame(someImpliedXfers, spanMapAccessor.getImpliedTransfers(accessor));
	}

	@Test
	void doesntRecomputeImpliedTransfersIfMetaMatches() {
		given(accessor.getSpanMap()).willReturn(span);
		given(accessor.getFunction()).willReturn(CryptoTransfer);
		given(dynamicProperties.maxTransferListSize()).willReturn(maxHbarAdjusts);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxTokenAdjusts);
		spanMapAccessor.setImpliedTransfers(accessor, someImpliedXfers);

		// when:
		subject.rationalizeSpan(accessor);

		// then:
		verify(impliedTransfersMarshal, never()).unmarshalFromGrpc(any(), any());
		assertSame(someImpliedXfers, spanMapAccessor.getImpliedTransfers(accessor));
	}

	@Test
	void recomputesImpliedTransfersIfMetaMatches() {
		given(accessor.getTxn()).willReturn(pretendXferTxn);
		given(accessor.getSpanMap()).willReturn(span);
		given(accessor.getFunction()).willReturn(CryptoTransfer);
		given(dynamicProperties.maxTransferListSize()).willReturn(maxHbarAdjusts);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxTokenAdjusts + 1);
		spanMapAccessor.setImpliedTransfers(accessor, someImpliedXfers);
		given(impliedTransfersMarshal.unmarshalFromGrpc(pretendXferTxn.getCryptoTransfer(), accessor.getPayer()))
				.willReturn(someOtherImpliedXfers);

		// when:
		subject.rationalizeSpan(accessor);

		// then:
		verify(impliedTransfersMarshal).unmarshalFromGrpc(pretendXferTxn.getCryptoTransfer(), accessor.getPayer());
		assertSame(someOtherImpliedXfers, spanMapAccessor.getImpliedTransfers(accessor));
	}

	@Test
	void recomputesImpliedTransfersIfCustomFeeChanges() {
		given(accessor.getTxn()).willReturn(pretendXferTxn);
		given(accessor.getSpanMap()).willReturn(span);
		given(accessor.getFunction()).willReturn(CryptoTransfer);
		given(dynamicProperties.maxTransferListSize()).willReturn(maxHbarAdjusts);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxTokenAdjusts + 1);
		spanMapAccessor.setImpliedTransfers(accessor, validImpliedTransfers);
		given(impliedTransfersMarshal.unmarshalFromGrpc(pretendXferTxn.getCryptoTransfer(), accessor.getPayer()))
				.willReturn(feeChangedImpliedTransfers);

		// when:
		subject.rationalizeSpan(accessor);

		// then:
		verify(impliedTransfersMarshal).unmarshalFromGrpc(pretendXferTxn.getCryptoTransfer(), accessor.getPayer());
		assertSame(feeChangedImpliedTransfers, spanMapAccessor.getImpliedTransfers(accessor));
	}
}
