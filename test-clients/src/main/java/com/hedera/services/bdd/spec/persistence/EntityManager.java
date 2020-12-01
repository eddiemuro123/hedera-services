package com.hedera.services.bdd.spec.persistence;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.suites.utils.validation.ValidationScenarios;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.hedera.services.bdd.spec.persistence.Entity.UNNEEDED_CREATE_OP;
import static java.util.stream.Collectors.toList;

public class EntityManager {
	static final Logger log = LogManager.getLogger(EntityManager.class);

	private final HapiApiSpec spec;

	public EntityManager(HapiApiSpec spec) {
		this.spec = spec;
	}

	Map<String, EntityMeta> registeredEntityMeta = new HashMap<>();
	List<Entity> entities = new ArrayList<>();

	public boolean init() {
		var entitiesDir = new File(spec.setup().persistentEntitiesDirPath());
		if (!entitiesDir.exists() || !entitiesDir.isDirectory()) {
			return true;
		}
		File[] yaml = entitiesDir.listFiles(f -> f.getAbsolutePath().endsWith(".yaml"));
		var yamlIn = new Yaml(new Constructor(Entity.class));
		for (File manifest : yaml) {
			try {
				log.info("Attempting to register an entity from '{}'", manifest.getPath());
				Entity entity = yamlIn.load(Files.newInputStream(manifest.toPath()));
				var name = entity.getName();
				if (registeredEntityMeta.containsKey(name)) {
					log.warn("Skipping entity from '{}', name '{}' already used!", manifest.getPath(), name);
				} else {
					entity.registerWhatIsKnown(spec);
					entities.add(entity);
					registeredEntityMeta.put(
							name,
							new EntityMeta(entity, manifest.getAbsolutePath(), entity.needsCreation()));
				}
			} catch (IOException e) {
				log.error("Could not deserialize entity from '{}'!", manifest.getPath(), e);
				return false;
			}
		}
		return true;
	}

	public void updateCreatedEntityManifests() {
		registeredEntityMeta.entrySet().stream()
				.map(Map.Entry::getValue)
				.filter(EntityMeta::neededCreation)
				.forEach(meta -> {
					var entity = meta.getEntity();
					var optionalCreatedId = extractCreated(entity.getCreateOp());
					optionalCreatedId.ifPresent(createdId -> {
						entity.setId(createdId);
						entity.setCreateOp(UNNEEDED_CREATE_OP);
						var yamlOut = new Yaml(new SkipNullRepresenter());
						var doc = yamlOut.dumpAs(meta.getEntity(), Tag.MAP, null);
						try {
							var writer = Files.newBufferedWriter(Paths.get(meta.getManifestLoc()));
							writer.write(doc);
							writer.close();
						} catch (IOException e) {
							log.warn("Could not update {} with created entity id!", entity.getName(), e);
						}
					});
				});
	}

	private Optional<EntityId> extractCreated(HapiTxnOp creationOp) {
		var receipt = creationOp.getLastReceipt();
		EntityId createdEntityId = null;
		if (receipt.hasAccountID()) {
			createdEntityId = new EntityId(HapiPropertySource.asAccountString(receipt.getAccountID()));
		} else if (receipt.hasTokenID()) {
			createdEntityId = new EntityId(HapiPropertySource.asTokenString(receipt.getTokenID()));
		} else if (receipt.hasTopicID()) {
			createdEntityId = new EntityId(HapiPropertySource.asTopicString(receipt.getTopicID()));
		} else if (receipt.hasContractID()) {
			createdEntityId = new EntityId(HapiPropertySource.asContractString(receipt.getContractID()));
		} else if (receipt.hasFileID()) {
			createdEntityId = new EntityId(HapiPropertySource.asFileString(receipt.getFileID()));
		}
		return Optional.ofNullable(createdEntityId);
	}

	public List<HapiSpecOperation> requiredCreations() {
		return entities.stream()
				.filter(Entity::needsCreation)
				.map(Entity::createOp)
				.collect(toList());
	}

	static class EntityMeta {
		private final Entity entity;
		private final String manifestLoc;
		private final boolean needsCreation;

		public EntityMeta(Entity entity, String manifestLoc, boolean needsCreation) {
			this.entity = entity;
			this.manifestLoc = manifestLoc;
			this.needsCreation = needsCreation;
		}

		public String getManifestLoc() {
			return manifestLoc;
		}

		public boolean neededCreation() {
			return needsCreation;
		}

		public Entity getEntity() {
			return entity;
		}
	}

	private static class SkipNullRepresenter extends Representer {
		@Override
		protected NodeTuple representJavaBeanProperty(
				Object javaBean,
				Property property,
				Object propertyValue,
				Tag customTag
		) {
			if (propertyValue == null) {
				return null;
			} else {
				return super.representJavaBeanProperty(javaBean, property, propertyValue, customTag);
			}
		}
	}
}
