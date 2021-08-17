package com.hedera.services.sigs.order;

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

import static com.hedera.services.security.ops.SystemOpAuthorization.AUTHORIZED;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileAppend;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileUpdate;

import com.hedera.services.config.AccountNumbers;
import com.hedera.services.config.EntityNumbers;
import com.hedera.services.security.ops.SystemOpPolicies;
import com.hederahashgraph.api.proto.java.TransactionBody;

/**
 * Implementation of {@link SignatureWaivers} that waives signatures based on the {@link
 * com.hedera.services.security.ops.SystemOpAuthorization} status of the transaction to which they
 * apply.
 *
 * <p>That is, it waives a signature if and only if the transaction is {@code AUTHORIZED} by the
 * injected {@link SystemOpPolicies}.
 *
 * <p>There is one exception. Even though the treasury account {@code 0.0.2} <b>is</b> authorized to
 * update itself with a new key, the standard waiver does not apply here, and a new key must sign;
 * https://github.com/hashgraph/hedera-services/issues/1890 has details.
 */
public class PolicyBasedSigWaivers implements SignatureWaivers {
  private final AccountNumbers accountNums;
  private final SystemOpPolicies opPolicies;

  public PolicyBasedSigWaivers(EntityNumbers entityNums, SystemOpPolicies opPolicies) {
    this.opPolicies = opPolicies;

    this.accountNums = entityNums.accounts();
  }

  @Override
  public boolean isAppendFileWaclWaived(TransactionBody fileAppendTxn) {
    assertTypeExpectation(fileAppendTxn.hasFileAppend());
    return opPolicies.check(fileAppendTxn, FileAppend) == AUTHORIZED;
  }

  @Override
  public boolean isTargetFileWaclWaived(TransactionBody fileUpdateTxn) {
    assertTypeExpectation(fileUpdateTxn.hasFileUpdate());
    return opPolicies.check(fileUpdateTxn, FileUpdate) == AUTHORIZED;
  }

  @Override
  public boolean isNewFileWaclWaived(TransactionBody fileUpdateTxn) {
    assertTypeExpectation(fileUpdateTxn.hasFileUpdate());
    return opPolicies.check(fileUpdateTxn, FileUpdate) == AUTHORIZED;
  }

  @Override
  public boolean isTargetAccountKeyWaived(TransactionBody cryptoUpdateTxn) {
    assertTypeExpectation(cryptoUpdateTxn.hasCryptoUpdateAccount());
    return opPolicies.check(cryptoUpdateTxn, CryptoUpdate) == AUTHORIZED;
  }

  @Override
  public boolean isNewAccountKeyWaived(TransactionBody cryptoUpdateTxn) {
    assertTypeExpectation(cryptoUpdateTxn.hasCryptoUpdateAccount());
    final var isAuthorized = opPolicies.check(cryptoUpdateTxn, CryptoUpdate) == AUTHORIZED;
    if (!isAuthorized) {
      return false;
    } else {
      final var targetNum =
          cryptoUpdateTxn.getCryptoUpdateAccount().getAccountIDToUpdate().getAccountNum();
      return targetNum != accountNums.treasury();
    }
  }

  private void assertTypeExpectation(boolean isExpectedType) {
    if (!isExpectedType) {
      throw new IllegalArgumentException("Given transaction is not of the expected type");
    }
  }
}
