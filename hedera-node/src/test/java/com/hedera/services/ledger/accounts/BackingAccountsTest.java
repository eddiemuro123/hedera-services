package com.hedera.services.ledger.accounts;

/*-
 * ‌
 * Hedera Services Node
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

import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.store.tokens.views.internals.PermHashInteger;
import com.hedera.services.utils.FcLong;
import com.hedera.test.factories.accounts.MerkleAccountFactory;
import com.hederahashgraph.api.proto.java.AccountID;
import com.swirlds.common.MutabilityException;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.merkle.utility.KeyedMerkleLong;
import com.swirlds.merkle.map.MerkleMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static com.hedera.test.utils.IdUtils.asAccount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.times;
import static org.mockito.BDDMockito.verify;

class BackingAccountsTest {
	private final AccountID a = asAccount("0.0.1");
	private final AccountID b = asAccount("0.0.2");
	private final AccountID c = asAccount("0.0.3");
	private final AccountID d = asAccount("0.0.4");
	private final PermHashInteger aKey = PermHashInteger.fromAccountId(a);
	private final PermHashInteger bKey = PermHashInteger.fromAccountId(b);
	private final PermHashInteger cKey = PermHashInteger.fromAccountId(c);
	private final PermHashInteger dKey = PermHashInteger.fromAccountId(d);
	private final MerkleAccount aValue = MerkleAccountFactory.newAccount().balance(123L).get();
	private final MerkleAccount bValue = MerkleAccountFactory.newAccount().balance(122L).get();
	private final MerkleAccount cValue = MerkleAccountFactory.newAccount().balance(121L).get();
	private final MerkleAccount dValue = MerkleAccountFactory.newAccount().balance(120L).get();

	private MerkleMap<PermHashInteger, MerkleAccount> map;
	private BackingAccounts subject;

	@BeforeEach
	private void setup() {
		map = mock(MerkleMap.class);
		given(map.keySet()).willReturn(Collections.emptySet());

		subject = new BackingAccounts(() -> map);
	}

	@Test
	void rebuildsFromChangedSources() {
		// setup:
		map = new MerkleMap<>();
		map.put(aKey, aValue);
		map.put(bKey, bValue);
		// and:
		subject = new BackingAccounts(() -> map);

		// when:
		map.clear();
		map.put(cKey, cValue);
		map.put(dKey, dValue);
		// and:
		subject.rebuildFromSources();

		// then:
		assertFalse(subject.existingAccounts.contains(a));
		assertFalse(subject.existingAccounts.contains(b));
		// and:
		assertTrue(subject.existingAccounts.contains(c));
		assertTrue(subject.existingAccounts.contains(d));
	}

	@Test
	void containsDelegatesToKnownActive() {
		// setup:
		subject.existingAccounts = Set.of(a, b);

		// expect:
		assertTrue(subject.contains(a));
		assertTrue(subject.contains(b));
		// and:
		verify(map, never()).containsKey(any());
	}

	@Test
	void putUpdatesKnownAccounts() {
		// when:
		subject.put(a, aValue);

		// then:
		assertTrue(subject.existingAccounts.contains(a));
		// and:
		verify(map, never()).containsKey(any());
	}

	@Test
	void getRefIsReadThrough() {
		given(map.getForModify(aKey)).willReturn(aValue);

		// expect:
		assertEquals(aValue, subject.getRef(a));
		assertEquals(aValue, subject.getRef(a));
		// and:
		verify(map, times(2)).getForModify(aKey);
	}

	@Test
	void removeUpdatesBothCacheAndDelegate() {
		// given:
		subject.existingAccounts.add(a);

		// when:
		subject.remove(a);

		// then:
		verify(map).remove(aKey);
		// and:
		assertFalse(subject.existingAccounts.contains(a));
	}

	@Test
	void returnsMutableRef() {
		given(map.getForModify(aKey)).willReturn(aValue);

		// when:
		MerkleAccount v = subject.getRef(a);

		// then:
		assertSame(aValue, v);
	}

	@Test
	void usesPutForMissing() {
		// given:
		subject.put(a, bValue);

		// expect:
		verify(map).put(aKey, bValue);
	}

	@Test
	void putDoesNothingIfPresent() {
		// setup:
		subject.existingAccounts.add(a);

		given(map.getForModify(aKey)).willReturn(aValue);

		// when:
		subject.getRef(a);
		subject.put(a, aValue);

		// then:
		verify(map, never()).replace(aKey, aValue);
	}

	@Test
	void returnsExpectedIds() {
		// setup:
		var s = Set.of(a, b, c, d);
		// given:
		subject.existingAccounts = s;

		// expect:
		assertSame(s, subject.idSet());
	}

	@Test
	void delegatesUnsafeRef() {
		given(map.get(aKey)).willReturn(aValue);

		// expect:
		assertEquals(aValue, subject.getImmutableRef(a));
	}

	@Test
	void twoPutsChangesG4M() throws ConstructableRegistryException {
		// setup:
		ConstructableRegistry.registerConstructable(
				new ClassConstructorPair(KeyedMerkleLong.class, KeyedMerkleLong::new));
		final var oneGrandKey = new FcLong(1000L);
		final var twoGrandKey = new FcLong(2000L);
		final var evilKey = new FcLong(666L);
		final var nonEvilKey = new FcLong(667L);

		/* Case 1: g4m a leaf; then put ONE new leaf; then change the mutable leaf and re-get to verify new value */
		final MerkleMap<FcLong, KeyedMerkleLong<FcLong>> firstMm = new MerkleMap<>();
		final var oneGrandEntry = new KeyedMerkleLong<>(oneGrandKey, 1000L);
		firstMm.put(oneGrandKey, oneGrandEntry);
		final var mutableOne = firstMm.getForModify(oneGrandKey);
		/* Putting just one new leaf */
		final var evilEntry = new KeyedMerkleLong<>(evilKey, 666L);
		firstMm.put(evilKey, evilEntry);
		/* Then the mutable value is retained */
		assertSame(mutableOne, firstMm.get(oneGrandKey));

		/* Case 2: g4m a leaf; then put TWO new leaves; then change the mutable leaf and re-get to verify new value */
		final var secondFcm = new MerkleMap<FcLong, KeyedMerkleLong<FcLong>>();
		final var twoGrandEntry = new KeyedMerkleLong<>(twoGrandKey, 2000L);
		final var evilEntry2 = new KeyedMerkleLong<>(evilKey, 666L);
		final var nonEvilEntry2 = new KeyedMerkleLong<>(nonEvilKey, 667L);
		secondFcm.put(twoGrandKey, twoGrandEntry);
		final var mutableTwo = secondFcm.getForModify(twoGrandEntry.getKey());
		/* Putting two new leaves now */
		secondFcm.put(evilEntry2.getKey(), evilEntry2);
		secondFcm.put(nonEvilEntry2.getKey(), nonEvilEntry2);
		/* And now changing the once-mutable value throws MutabilityException */
		assertThrows(MutabilityException.class, mutableTwo::increment);
	}
}
