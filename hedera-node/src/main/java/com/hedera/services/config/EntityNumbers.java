package com.hedera.services.config;

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

public class EntityNumbers {
	public static final long UNKNOWN_NUMBER = Long.MIN_VALUE;

	private final FileNumbers fileNumbers;
	private final AccountNumbers accountNumbers;

	public EntityNumbers(FileNumbers fileNumbers, AccountNumbers accountNumbers) {
		this.fileNumbers = fileNumbers;
		this.accountNumbers = accountNumbers;
	}

	public FileNumbers ofFile() {
		return fileNumbers;
	}

	public AccountNumbers ofAccount() {
		return accountNumbers;
	}
}
