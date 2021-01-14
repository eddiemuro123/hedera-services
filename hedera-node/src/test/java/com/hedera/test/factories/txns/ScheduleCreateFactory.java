package com.hedera.test.factories.txns;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
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

import com.hedera.test.factories.scenarios.TxnHandlingScenario;
import com.hederahashgraph.api.proto.java.ScheduleCreateTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;

public class ScheduleCreateFactory extends SignedTxnFactory<ScheduleCreateFactory> {
    private boolean omitAdmin = false;

    private ScheduleCreateFactory() {}

    public static ScheduleCreateFactory newSignedScheduleCreate() {
        return new ScheduleCreateFactory();
    }

    public ScheduleCreateFactory missingAdmin() {
        omitAdmin = true;
        return this;
    }

    @Override
    protected ScheduleCreateFactory self() {
        return this;
    }

    @Override
    protected long feeFor(Transaction signedTxn, int numPayerKeys) {
        return 0;
    }

    @Override
    protected void customizeTxn(TransactionBody.Builder txn) {
        var op = ScheduleCreateTransactionBody.newBuilder();
        if (!omitAdmin) {
            op.setAdminKey(TxnHandlingScenario.SCHEDULE_ADMIN_KT.asKey());
        }
        txn.setScheduleCreate(op);
    }
}
