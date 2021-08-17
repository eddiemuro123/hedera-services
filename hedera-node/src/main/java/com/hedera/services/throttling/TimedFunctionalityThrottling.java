package com.hedera.services.throttling;

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

import com.hedera.services.utils.TxnAccessor;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import java.time.Instant;

public interface TimedFunctionalityThrottling extends FunctionalityThrottling {
  @Override
  default boolean shouldThrottleTxn(TxnAccessor accessor) {
    return shouldThrottleTxn(accessor, Instant.now());
  }

  @Override
  default boolean shouldThrottleQuery(HederaFunctionality queryFunction) {
    return shouldThrottleQuery(queryFunction, Instant.now());
  }

  boolean shouldThrottleTxn(TxnAccessor accessor, Instant now);

  boolean shouldThrottleQuery(HederaFunctionality queryFunction, Instant now);
}
