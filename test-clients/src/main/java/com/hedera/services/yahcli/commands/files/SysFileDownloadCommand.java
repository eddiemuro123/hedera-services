package com.hedera.services.yahcli.commands.files;

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
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.SysFileDownloadSuite;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.util.concurrent.Callable;

import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

@Command(
		name = "download",
		subcommands = { CommandLine.HelpCommand.class },
		description = "Download a system file")
public class SysFileDownloadCommand implements Callable<Integer> {
	@ParentCommand
	SysFilesCommand sysFilesCommand;

	@CommandLine.Option(names = { "-d", "--dest-dir" },
			paramLabel = "destination directory",
			defaultValue = "{network}/sysfiles/")
	String destDir;

	@CommandLine.Option(names = { "-v", "--version" },
			paramLabel = "protobuf version",
			defaultValue = "13")
	String version;

	@Parameters(
			arity = "1..*",
			paramLabel = "<sysfiles>",
			description = "system file names ('book', 'details', 'rates', 'fees', 'props', 'permissions')  \n or numbers" +
					"\nor 'all' to download all system files ")
	String[] sysFiles;

	@Override
	public Integer call() throws Exception {
		var config = ConfigManager.from(sysFilesCommand.getYahcli());
		config.assertNoMissingDefaults();
		COMMON_MESSAGES.printGlobalInfo(config);

		if (destDir.startsWith("{network}")) {
			destDir = config.getTargetName() + File.separator + "sysfiles";
		}
		ConfigUtils.ensureDir(destDir);
		if (destDir.endsWith(File.separator)) {
			destDir = destDir.substring(0, destDir.length() - 1);
		}

		var delegate = new SysFileDownloadSuite(destDir, config.asSpecConfig(), sysFiles, version);
		delegate.runSuiteSync();

		return 0;
	}
}
