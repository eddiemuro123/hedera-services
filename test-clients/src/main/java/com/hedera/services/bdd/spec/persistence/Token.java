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
import com.hedera.services.bdd.spec.HapiSpecOperation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

import static com.hedera.services.bdd.spec.persistence.PemKey.RegistryForms.asAdminKeyFor;
import static com.hedera.services.bdd.spec.persistence.PemKey.RegistryForms.asFreezeKeyFor;
import static com.hedera.services.bdd.spec.persistence.PemKey.RegistryForms.asKycKeyFor;
import static com.hedera.services.bdd.spec.persistence.PemKey.RegistryForms.asSupplyKeyFor;
import static com.hedera.services.bdd.spec.persistence.PemKey.RegistryForms.asWipeKeyFor;
import static com.hedera.services.bdd.spec.persistence.PemKey.adminKeyFor;
import static com.hedera.services.bdd.spec.persistence.PemKey.freezeKeyFor;
import static com.hedera.services.bdd.spec.persistence.PemKey.kycKeyFor;
import static com.hedera.services.bdd.spec.persistence.PemKey.supplyKeyFor;
import static com.hedera.services.bdd.spec.persistence.PemKey.wipeKeyFor;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;

public class Token {
	static final Logger log = LogManager.getLogger(Token.class);

	private static final String DEFAULT_SYMBOL = "S6T";
	private static final String DEFAULT_HEDERA_NAME = "SOMNOLENT";
	private static final String UNSPECIFIED_TREASURY = null;

	private PemKey kycKey = Entity.UNUSED_KEY;
	private PemKey wipeKey = Entity.UNUSED_KEY;
	private PemKey adminKey = Entity.UNUSED_KEY;
	private PemKey supplyKey = Entity.UNUSED_KEY;
	private PemKey freezeKey = Entity.UNUSED_KEY;

	private String symbol = DEFAULT_SYMBOL;
	private String hederaName = DEFAULT_HEDERA_NAME;
	private String tokenTreasury = UNSPECIFIED_TREASURY;

	public void registerWhatIsKnown(HapiApiSpec spec, String name, Optional<EntityId> entityId) {
		if (kycKey != Entity.UNUSED_KEY) {
			kycKey.registerWith(spec, asKycKeyFor(name));
		}
		if (wipeKey != Entity.UNUSED_KEY) {
			wipeKey.registerWith(spec, asWipeKeyFor(name));
		}
		if (adminKey != Entity.UNUSED_KEY) {
			adminKey.registerWith(spec, asAdminKeyFor(name));
		}
		if (supplyKey != Entity.UNUSED_KEY) {
			supplyKey.registerWith(spec, asSupplyKeyFor(name));
		}
		if (freezeKey != Entity.UNUSED_KEY) {
			freezeKey.registerWith(spec, asFreezeKeyFor(name));
		}
		entityId.ifPresent(id -> {
			spec.registry().saveName(name, hederaName);
			spec.registry().saveSymbol(name, symbol);
			spec.registry().saveTokenId(name, id.asToken());
		});
	}

	public HapiSpecOperation createOp(String name) {
		var op = tokenCreate(name)
				.advertisingCreation()
				.symbol(symbol)
				.name(hederaName);

		if (tokenTreasury != UNSPECIFIED_TREASURY) {
			op.treasury(tokenTreasury);
		}

		if (kycKey != Entity.UNUSED_KEY) {
			op.kycKey(kycKeyFor(name));
		}
		if (wipeKey != Entity.UNUSED_KEY) {
			op.wipeKey(wipeKeyFor(name));
		}
		if (adminKey != Entity.UNUSED_KEY) {
			op.adminKey(adminKeyFor(name));
		}
		if (freezeKey != Entity.UNUSED_KEY) {
			op.freezeKey(freezeKeyFor(name));
		}
		if (supplyKey != Entity.UNUSED_KEY) {
			op.supplyKey(supplyKeyFor(name));
		}

		return op;
	}

	public String getTokenTreasury() {
		return tokenTreasury;
	}

	public void setTokenTreasury(String tokenTreasury) {
		this.tokenTreasury = tokenTreasury;
	}

	public PemKey getKycKey() {
		return kycKey;
	}

	public void setKycKey(PemKey kycKey) {
		this.kycKey = kycKey;
	}

	public PemKey getWipeKey() {
		return wipeKey;
	}

	public void setWipeKey(PemKey wipeKey) {
		this.wipeKey = wipeKey;
	}

	public PemKey getAdminKey() {
		return adminKey;
	}

	public void setAdminKey(PemKey adminKey) {
		this.adminKey = adminKey;
	}

	public PemKey getSupplyKey() {
		return supplyKey;
	}

	public void setSupplyKey(PemKey supplyKey) {
		this.supplyKey = supplyKey;
	}

	public PemKey getFreezeKey() {
		return freezeKey;
	}

	public void setFreezeKey(PemKey freezeKey) {
		this.freezeKey = freezeKey;
	}
}
