package com.hedera.services.fees.calculation.contract.queries;

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

import com.hedera.services.context.primitives.StateView;
import com.hedera.services.fees.calculation.QueryResourceUsageEstimator;
import com.hedera.services.queries.contract.GetContractRecordsAnswer;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.fee.SmartContractFeeBuilder;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class GetContractRecordsResourceUsage implements QueryResourceUsageEstimator {
	private final SmartContractFeeBuilder usageEstimator;

	@Inject
	public GetContractRecordsResourceUsage(final SmartContractFeeBuilder usageEstimator) {
		this.usageEstimator = usageEstimator;
	}

	@Override
	public boolean applicableTo(final Query query) {
		return query.hasContractGetRecords();
	}

	@Override
	public FeeData usageGiven(final Query query, final StateView view) {
		return usageGivenType(query, view, query.getContractGetRecords().getHeader().getResponseType());
	}

	@Override
	public FeeData usageGivenType(final Query query, final StateView view, final ResponseType type) {
		return usageEstimator.getContractRecordsQueryFeeMatrices(GetContractRecordsAnswer.GUARANTEED_EMPTY_PAYER_RECORDS,
				type);
	}
}
