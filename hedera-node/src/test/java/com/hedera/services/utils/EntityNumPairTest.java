package com.hedera.services.utils;

import com.hedera.services.state.merkle.internals.BitPackUtils;
import com.hedera.services.store.models.Account;
import com.hedera.services.store.models.Id;
import com.hedera.services.store.models.NftId;
import com.hedera.services.store.models.Token;
import com.hedera.services.store.models.TokenRelationship;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EntityNumPairTest {
	@Test
	void overridesJavaLangImpl() {
		final var v = 1_234_567;

		final var subject = new EntityNumPair(v);

		assertNotEquals(Long.valueOf(v).hashCode(), subject.hashCode());
	}

	@Test
	void equalsWorks() {
		final var a = new EntityNumPair(1);
		final var b = new EntityNumPair(2);
		final var c = a;

		assertNotEquals(a, b);
		assertNotEquals(null, a);
		assertNotEquals(a, new Object());
		assertEquals(a, c);
	}

	@Test
	void usesExpectedBitPacking() {
		// given:
		final var expected = new EntityNumPair(BitPackUtils.packedNums(1, 2));

		// expect:
		assertEquals(expected, EntityNumPair.fromLongs(1, 2));
	}

	@Test
	void factoryFromNftIdWorks() {
		// setup:
		final var expected = EntityNumPair.fromLongs(1, 2);
		final var nftId = new NftId(0, 0, 1, 2);

		// given:
		final var actual = EntityNumPair.fromNftId(nftId);

		// expect:
		assertEquals(expected, actual);
	}

	@Test
	void factoryFromModelRelWorks() {
		// setup:
		final var expected = EntityNumPair.fromLongs(1, 2);
		final var modelRel = new TokenRelationship(
				new Token(new Id(0 , 0, 2)),
				new Account(new Id(0 , 0, 1)));

		// given:
		final var actual = EntityNumPair.fromModelRel(modelRel);

		// expect:
		assertEquals(expected, actual);
	}

	@Test
	void hiPhiAccessorWorks() {
		// setup:
		final long bigNum = (long)Integer.MAX_VALUE + 123;
		final var expected = EntityNum.fromLong(bigNum);

		// given:
		final var subject = EntityNumPair.fromLongs(bigNum, 1);

		// when:
		final var hi = subject.getHiPhi();

		// then:
		assertEquals(expected, hi);
	}

	@Test
	void accountTokenReprWorks() {
		// given:
		final var subject = EntityNumPair.fromLongs(1, 2);

		// when:
		final var pairRepr = subject.asAccountTokenRel();

		// then:
		assertEquals(1, pairRepr.getLeft().getAccountNum());
		assertEquals(2, pairRepr.getRight().getTokenNum());
	}

	@Test
	void toStringWorks() {
		// given:
		final var subject = EntityNumPair.fromLongs(1, 2);

		// expect:
		assertEquals("PermHashLong(1, 2)", subject.toString());
	}
}