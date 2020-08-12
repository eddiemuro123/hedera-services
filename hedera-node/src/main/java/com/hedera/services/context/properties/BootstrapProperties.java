package com.hedera.services.context.properties;

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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableSet;
import static java.util.Set.of;
import static java.util.Map.entry;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

public class BootstrapProperties implements PropertySource {
	private static Map<String, Object> MISSING_PROPS = null;

	private static Function<String, InputStream> nullableResourceStreamProvider =
			BootstrapProperties.class.getClassLoader()::getResourceAsStream;

	static Logger log = LogManager.getLogger(BootstrapProperties.class);

	static ThrowingStreamProvider resourceStreamProvider = resource -> {
		var in = nullableResourceStreamProvider.apply(resource);
		if (in == null) {
			throw new IOException(String.format("Resource '%s' cannot be loaded.", resource));
		}
		return in;
	};

	String BOOTSTRAP_PROPS_RESOURCE = "bootstrap.properties";

	Map<String, Object> bootstrapProps = MISSING_PROPS;

	void initPropsFromResource() {
		var resourceProps = new Properties();
		load(BOOTSTRAP_PROPS_RESOURCE, resourceProps);

		Set<String> unrecognizedProps = new HashSet<>(resourceProps.stringPropertyNames());
		unrecognizedProps.removeAll(BOOTSTRAP_PROP_NAMES);
		if (!unrecognizedProps.isEmpty()) {
			var msg = String.format(
					"'%s' contains unrecognized properties: %s!",
					BOOTSTRAP_PROPS_RESOURCE,
					unrecognizedProps);
			throw new IllegalStateException(msg);
		}
		var missingProps = BOOTSTRAP_PROP_NAMES.stream()
				.filter(name -> !resourceProps.containsKey(name))
				.sorted()
				.collect(toList());
		if (!missingProps.isEmpty()) {
			var msg = String.format(
					"'%s' is missing properties: %s!",
					BOOTSTRAP_PROPS_RESOURCE,
					missingProps);
			throw new IllegalStateException(msg);
		}

		bootstrapProps = new HashMap<>();
		BOOTSTRAP_PROP_NAMES
				.stream()
				.forEach(prop -> bootstrapProps.put(
						prop,
						BOOTSTRAP_PROP_TRANSFORMS.getOrDefault(prop, s -> s)
								.apply(resourceProps.getProperty(prop))));

		var msg = "Resolved bootstrap and global/static properties:\n  " + BOOTSTRAP_PROP_NAMES.stream()
				.sorted()
				.map(name -> String.format("%s=%s", name, bootstrapProps.get(name)))
				.collect(Collectors.joining("\n  "));
		log.info(msg);
	}

	private void load(String resource, Properties intoProps) {
		InputStream fin;
		try {
			fin = resourceStreamProvider.newInputStream(resource);
			intoProps.load(fin);
		} catch (IOException e) {
			throw new IllegalStateException(
					String.format("'%s' could not be loaded!", resource),
					e);
		}
	}

	void ensureProps() {
		if (bootstrapProps == MISSING_PROPS) {
			initPropsFromResource();
		}
	}

	@Override
	public boolean containsProperty(String name) {
		return BOOTSTRAP_PROP_NAMES.contains(name);
	}

	@Override
	public Object getProperty(String name) {
		ensureProps();
		if (bootstrapProps.containsKey(name)) {
			return bootstrapProps.get(name);
		} else {
			throw new IllegalArgumentException(String.format("No such property '%s'!", name));
		}
	}

	@Override
	public Set<String> allPropertyNames() {
		return BOOTSTRAP_PROP_NAMES;
	}

	private static final Set<String> BOOTSTRAP_PROPS = Set.of(
			"bootstrap.feeSchedulesJson.resource",
			"bootstrap.genesisB64Keystore.keyName",
			"bootstrap.genesisB64Keystore.path",
			"bootstrap.genesisPemPassphrase.path",
			"bootstrap.genesisPem.path",
			"bootstrap.hapiPermissions.path",
			"bootstrap.ledger.nodeAccounts.initialBalance",
			"bootstrap.ledger.systemAccounts.initialBalance",
			"bootstrap.ledger.systemAccounts.recordThresholds",
			"bootstrap.networkProperties.path",
			"bootstrap.rates.currentHbarEquiv",
			"bootstrap.rates.currentCentEquiv",
			"bootstrap.rates.currentExpiry",
			"bootstrap.rates.nextHbarEquiv",
			"bootstrap.rates.nextCentEquiv",
			"bootstrap.rates.nextExpiry",
			"bootstrap.system.entityExpiry"
	);

	private static final Set<String> GLOBAL_STATIC_PROPS = Set.of(
			"accounts.addressBookAdmin",
			"accounts.exchangeRatesAdmin",
			"accounts.feeSchedulesAdmin",
			"accounts.freezeAdmin",
			"accounts.systemAdmin",
			"accounts.systemAdmin.firstManaged",
			"accounts.systemAdmin.lastManaged",
			"accounts.systemDeleteAdmin",
			"accounts.systemUndeleteAdmin",
			"accounts.treasury",
			"files.addressBook",
			"files.networkProperties",
			"files.exchangeRates",
			"files.feeSchedules",
			"files.hapiPermissions",
			"files.nodeDetails",
			"hedera.numReservedSystemEntities",
			"hedera.realm",
			"hedera.shard",
			"ledger.numSystemAccounts",
			"ledger.totalTinyBarFloat"
	);

	public static final Set<String> BOOTSTRAP_PROP_NAMES = unmodifiableSet(
			Stream.of(BOOTSTRAP_PROPS, GLOBAL_STATIC_PROPS)
					.flatMap(Set::stream)
					.collect(toSet()));

	private static final Map<String, Function<String, Object>> BOOTSTRAP_PROP_TRANSFORMS = Map.ofEntries(
			entry("accounts.addressBookAdmin", AS_LONG),
			entry("accounts.exchangeRatesAdmin", AS_LONG),
			entry("accounts.feeSchedulesAdmin", AS_LONG),
			entry("accounts.freezeAdmin", AS_LONG),
			entry("accounts.systemAdmin", AS_LONG),
			entry("accounts.systemDeleteAdmin", AS_LONG),
			entry("accounts.systemUndeleteAdmin", AS_LONG),
			entry("accounts.systemAdmin.firstManaged", AS_LONG),
			entry("accounts.systemAdmin.lastManaged", AS_LONG),
			entry("accounts.treasury", AS_LONG),
			entry("files.addressBook", AS_LONG),
			entry("files.networkProperties", AS_LONG),
			entry("files.exchangeRates", AS_LONG),
			entry("files.feeSchedules", AS_LONG),
			entry("files.hapiPermissions", AS_LONG),
			entry("files.nodeDetails", AS_LONG),
			entry("hedera.numReservedSystemEntities", AS_LONG),
			entry("hedera.realm", AS_LONG),
			entry("hedera.shard", AS_LONG),
			entry("bootstrap.ledger.nodeAccounts.initialBalance", AS_LONG),
			entry("bootstrap.ledger.systemAccounts.initialBalance", AS_LONG),
			entry("bootstrap.ledger.systemAccounts.recordThresholds", AS_LONG),
			entry("bootstrap.rates.currentHbarEquiv", AS_INT),
			entry("bootstrap.rates.currentCentEquiv", AS_INT),
			entry("bootstrap.rates.currentExpiry", AS_LONG),
			entry("bootstrap.rates.nextHbarEquiv", AS_INT),
			entry("bootstrap.rates.nextCentEquiv", AS_INT),
			entry("bootstrap.rates.nextExpiry", AS_LONG),
			entry("bootstrap.system.entityExpiry", AS_LONG),
			entry("ledger.numSystemAccounts", AS_INT),
			entry("ledger.totalTinyBarFloat", AS_LONG)
	);
}
