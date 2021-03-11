package com.hedera.services.yahcli.commands.accounts;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.hedera.services.yahcli.config.ConfigManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.SplittableRandom;
import java.util.concurrent.Callable;

import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

@Command(
		name = "balance",
		subcommands = { HelpCommand.class },
		description = "Retrieve the balance of account(s) on the target network")
public class BalanceCommand implements Callable<Integer> {
	@ParentCommand
	AccountsCommand accountsCommand;

	@Parameters(
			arity = "1..*",
			paramLabel = "<accounts>",
			description = "account names or numbers")
	String[] accounts;

	@Override
	public Integer call() throws Exception {
		var config = ConfigManager.from(accountsCommand.getYahcli());
		config.assertNoMissingDefaults();
		COMMON_MESSAGES.printGlobalInfo(config);

		var r = new SplittableRandom();
		for (String account : accounts) {
			System.out.println(String.format(
					" - Account %s has balance %d",
					account,
					r.nextInt(0, Integer.MAX_VALUE)));
		}
		return 0;
	}
}
