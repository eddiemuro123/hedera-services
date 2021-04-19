package com.hedera.services.yahcli.commands.validation;

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

import com.hedera.services.yahcli.Yahcli;
import com.hedera.services.yahcli.output.CommonMessages;
import com.hedera.services.yahcli.suites.SchedulesValidationSuite;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;

import static com.hedera.services.bdd.spec.persistence.EntityManager.accountLoc;
import static com.hedera.services.bdd.spec.persistence.EntityManager.scheduleLoc;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;
import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

@Command(
		name = "validate",
		subcommands = {
				picocli.CommandLine.HelpCommand.class,
		},
		description = "Perform system file operations")
public class ValidationCommand implements Callable<Integer> {
	public static final String PAYER = "yahcliPayer";
	public static final String SENDER = "yahcliSender";
	public static final String RECEIVER = "yahcliReceiver";
	public static final String MUTABLE_SCHEDULE = "yahcliMutablePendingXfer";
	public static final String IMMUTABLE_SCHEDULE = "yahcliImmutablePendingXfer";

	public static final String checkBoxed(String achievement)  {
		return "[X] " + achievement;
	}

	@ParentCommand
	Yahcli yahcli;

	@CommandLine.Parameters(
			arity = "1..*",
			paramLabel = "<services>",
			description = "one or more from { crypto, file, contract, consensus, token, scheduling }; or 'all'")
	private String[] services;

	@Override
	public Integer call() throws Exception {
		var config = configFrom(yahcli);

		var persistenceDir = config.getTargetName();
		var specConfig = config.asSpecConfig();
		specConfig.put("persistentEntities.dir.path", persistenceDir);

		COMMON_MESSAGES.beginBanner("\uD83D\uDD25", "validation of " + Arrays.toString(services));

		if (isSchedulingRequested()) {
			validateScheduling(specConfig);
		}

		return 0;
	}

	private void validateScheduling(Map<String, String> specConfig) {
		var persistenceDir = specConfig.get("persistentEntities.dir.path");
		ensureNoTemplatesExistFor(new String[] {
				scheduleLoc(persistenceDir, yaml(MUTABLE_SCHEDULE)),
				scheduleLoc(persistenceDir, yaml(IMMUTABLE_SCHEDULE))
		});
		ensureTemplatesExistFor(new String[] {
				accountLoc(persistenceDir, yaml(PAYER)),
				accountLoc(persistenceDir, yaml(SENDER)),
				accountLoc(persistenceDir, yaml(RECEIVER)),
				scheduleLoc(persistenceDir, yaml(MUTABLE_SCHEDULE)),
				scheduleLoc(persistenceDir, yaml(IMMUTABLE_SCHEDULE))
		});
		new SchedulesValidationSuite(specConfig).runSuiteSync();
	}

	private String yaml(String entity) {
		return entity + ".yaml";
	}

	private void ensureNoTemplatesExistFor(String[] entityLocs) {
		for (var entityLoc : entityLocs) {
			var f = new File(entityLoc);
			if (f.exists()) {
				f.delete();
			}
		}
	}

	private void ensureTemplatesExistFor(String[] entityLocs) {
		for (var entityLoc : entityLocs) {
			var f = new File(entityLoc);
			if (!f.exists()) {
				var dir = new File(entityLoc.substring(0, entityLoc.lastIndexOf(File.separator)));
				dir.mkdirs();
				var tplResource = "yahcli" + entityLoc.substring(entityLoc.indexOf(File.separator));
				System.out.println(". Creating " + entityLoc + " from resource template '" + tplResource + "'...");
				try (var fout = Files.newOutputStream(Paths.get(entityLoc))) {
					ValidationCommand.class.getClassLoader().getResourceAsStream(tplResource).transferTo(fout);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		}
	}

	private boolean isConsensusRequested() {
		return isRequested("consensus");
	}

	private boolean isTokenRequested() {
		return isRequested("token");
	}

	private boolean isFileRequested() {
		return isRequested("file");
	}

	private boolean isContractRequested() {
		return isRequested("contract");
	}

	private boolean isCryptoRequested() {
		return isRequested("crypto");
	}

	private boolean isSchedulingRequested() {
		return isRequested("scheduling");
	}

	private boolean isRequested(String prefix) {
		return (services.length == 1 && "all".equals(services[0])) || Arrays.stream(services).anyMatch(prefix::equals);
	}

	public Yahcli getYahcli() {
		return yahcli;
	}
}
