package com.hedera.services.fees.calculation.file.txns;

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

import static org.junit.jupiter.api.Assertions.*;

import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.fee.FileFeeBuilder;
import com.hederahashgraph.fee.SigValueObj;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.BDDMockito.*;

class SystemDeleteFileResourceUsageTest {
	private SigValueObj sigUsage;
	private FileFeeBuilder usageEstimator;
	private SystemDeleteFileResourceUsage subject;

	private TransactionBody nonSystemDeleteFileTxn;
	private TransactionBody systemDeleteFileTxn;

	@BeforeEach
	private void setup() throws Throwable {
		systemDeleteFileTxn = mock(TransactionBody.class);
		given(systemDeleteFileTxn.hasSystemDelete()).willReturn(true);

		nonSystemDeleteFileTxn = mock(TransactionBody.class);
		given(nonSystemDeleteFileTxn.hasSystemDelete()).willReturn(false);

		sigUsage = mock(SigValueObj.class);
		usageEstimator = mock(FileFeeBuilder.class);

		subject = new SystemDeleteFileResourceUsage(usageEstimator);
	}

	@Test
	public void recognizesApplicability() {
		// expect:
		assertTrue(subject.applicableTo(systemDeleteFileTxn));
		assertFalse(subject.applicableTo(nonSystemDeleteFileTxn));
	}

	@Test
	public void delegatesToCorrectEstimate() throws Exception {
		// when:
		subject.usageGiven(systemDeleteFileTxn, sigUsage, null);

		// then:
		verify(usageEstimator).getSystemDeleteFileTxFeeMatrices(systemDeleteFileTxn, sigUsage);
	}
}
