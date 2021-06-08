package com.hedera.services.state.merkle.internals;

import com.hedera.services.store.models.Id;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyOnWriteIdsTest {
	private List<long[]> someIds = List.of(
			new long[] { 3, 2, 1 },
			new long[] { 4, 2, 1 },
			new long[] { 5, 2, 1 });
	private List<long[]> someMoreIds = List.of(
			new long[] { 3, 2, 1 },
			new long[] { 2, 0, 0 },
			new long[] { 98, 0, 0 });

	@Test
	void usesCopyOnWriteSemantics() {
		// setup:
		final var a = new CopyOnWriteIds();
		a.add(someIds);
		final var aCopy = a.copy();
		// and:
		final var aRepr = "[1.2.4, 1.2.5]";
		final var aCopyRepr = "[0.0.2, 1.2.3, 1.2.3, 1.2.4, 1.2.5, 0.0.98]";

		// when:
		a.remove(listHas(someMoreIds));
		aCopy.add(someMoreIds);

		// then:
		assertEquals(aRepr, a.toReadableIdList());
		assertEquals(aCopyRepr, aCopy.toReadableIdList());
	}

	@Test
	void containsWorks() {
		// setup:
		final var present = new Id(1, 2, 4);
		final var absent = new Id(1, 2, 666);

		// given:
		final var subject = new CopyOnWriteIds();
		subject.add(someIds);

		// expect:
		assertTrue(subject.contains(present));
		assertFalse(subject.contains(absent));
	}

	@Test
	void degenerateEqualsWorks() {
		// given:
		final var a = new CopyOnWriteIds(new long[] { 1, 2, 3, 4, 5, 6 });
		final var b = a;

		// expect:
		assertEquals(a, b);
		assertNotEquals(a, new Object());
		assertNotEquals(a, null);
	}

	@Test
	void toStringWorks() {
		// setup:
		final var desired = "CopyOnWriteIds{ids=[3.2.1, 6.5.4]}";
		final var a = new CopyOnWriteIds(new long[] { 1, 2, 3, 4, 5, 6 });

		// expect:
		assertEquals(desired, a.toString());
	}

	private Predicate<long[]> listHas(List<long[]> l) {
		return nativeId -> {
			for (int i = 0, n = l.size(); i < n; i++) {
				if (Arrays.equals(nativeId, l.get(i))) {
					return true;
				}
			}
			return false;
		};
	}
}