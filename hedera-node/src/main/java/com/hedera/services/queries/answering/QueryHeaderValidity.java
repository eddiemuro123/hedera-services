package com.hedera.services.queries.answering;

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

import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseType;

import java.util.EnumSet;

import static com.hedera.services.utils.MiscUtils.activeHeaderFrom;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MISSING_QUERY_HEADER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_STATE_PROOF;
import static com.hederahashgraph.api.proto.java.ResponseType.COST_ANSWER_STATE_PROOF;

public class QueryHeaderValidity {
	private EnumSet<ResponseType> UNSUPPORTED_RESPONSE_TYPES = EnumSet.of(ANSWER_STATE_PROOF, COST_ANSWER_STATE_PROOF);

	public ResponseCodeEnum checkHeader(Query query) {
		final var bestGuessHeader = activeHeaderFrom(query);
		if (bestGuessHeader.isEmpty()) {
			return MISSING_QUERY_HEADER;
		} else {
			final var type = bestGuessHeader.get().getResponseType();
			return UNSUPPORTED_RESPONSE_TYPES.contains(type) ? NOT_SUPPORTED : OK;
		}
	}
}
