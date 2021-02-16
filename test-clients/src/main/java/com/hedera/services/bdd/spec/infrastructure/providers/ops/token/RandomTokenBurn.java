package com.hedera.services.bdd.spec.infrastructure.providers.ops.token;

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

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenCreate;
import com.hederahashgraph.api.proto.java.TokenID;

import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

//import static com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccount.INITIAL_BALANCE;
import static com.hedera.services.bdd.spec.keys.KeyShape.randomly;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiApiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static java.util.stream.Collectors.toList;

public class RandomTokenBurn implements OpProvider {

	private final int numOfTestTokens = 100;
	private final long DEFAULT_MAX_SUPPLY = 1_000L;
	private final AtomicInteger opNo = new AtomicInteger();
	private final EntityNameProvider<Key> keys;
	private final RegistrySourcedNameProvider<TokenID> tokens;
	private final RegistrySourcedNameProvider<AccountID> accounts;

	private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(
			TOKEN_IS_IMMUTABLE,
			TOKEN_WAS_DELETED,
			/* The randomly chosen treasury might already have tokens.maxPerAccount associated tokens */
			TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED,
			TOKEN_HAS_NO_SUPPLY_KEY
			);



	public RandomTokenBurn(
			EntityNameProvider<Key> keys,
			RegistrySourcedNameProvider<TokenID> tokens,
			RegistrySourcedNameProvider<AccountID> accounts
	) {
		this.keys = keys;
		this.tokens = tokens;
		this.accounts = accounts;
	}

	@Override
	public List<HapiSpecOperation> suggestedInitializers() {
		return IntStream.
				range(0, numOfTestTokens)
				.mapToObj(i -> 	tokenCreate("token" + i)
								.noLogging()
								.deferStatusResolution()
								.payingWith(UNIQUE_PAYER_ACCOUNT)
				)
				.collect(toList());
	}

	@Override
	public Optional<HapiSpecOperation> get() {
		Optional<String> token = tokens.getQualifying();
		if (token.isEmpty())	{
			return Optional.empty();
		}

		var amount = BASE_RANDOM.nextLong(1, DEFAULT_MAX_SUPPLY);

		var op = burnToken(token.get(), amount)
				.payingWith(GENESIS)
				.hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS)
				.hasKnownStatusFrom(permissibleOutcomes)
		;

		return Optional.of(op);
	}
	private String my(String opName) {
		return unique(opName, RandomToken.class);
	}

}
