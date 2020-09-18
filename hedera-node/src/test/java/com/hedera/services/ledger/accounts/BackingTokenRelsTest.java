package com.hedera.services.ledger.accounts;

import com.hedera.services.state.merkle.MerkleEntityAssociation;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.swirlds.fcmap.FCMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;

import java.util.Collections;

import static com.hedera.services.ledger.accounts.BackingTokenRels.asKey;
import static com.hedera.services.state.merkle.MerkleEntityAssociation.fromAccountTokenRel;
import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hedera.test.utils.IdUtils.asToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.times;
import static org.mockito.BDDMockito.verify;

@RunWith(JUnitPlatform.class)
class BackingTokenRelsTest {
	long aBalance = 100, bBalance = 200, cBalance = 300;
	boolean aFrozen = true, bFrozen = false, cFrozen = true;
	boolean aKyc = false, bKyc = true, cKyc = false;
	AccountID a = asAccount("1.2.3");
	AccountID b = asAccount("3.2.1");
	AccountID c = asAccount("4.3.0");
	TokenID at = asToken("9.8.7");
	TokenID bt = asToken("9.8.6");
	TokenID ct = asToken("9.8.5");

	MerkleEntityAssociation aKey = fromAccountTokenRel(a, at);
	MerkleEntityAssociation bKey = fromAccountTokenRel(b, bt);
	MerkleEntityAssociation cKey = fromAccountTokenRel(c, ct);
	MerkleTokenRelStatus aValue = new MerkleTokenRelStatus(aBalance, aFrozen, aKyc);
	MerkleTokenRelStatus bValue = new MerkleTokenRelStatus(bBalance, bFrozen, bKyc);
	MerkleTokenRelStatus cValue = new MerkleTokenRelStatus(cBalance, cFrozen, cKyc);

	private FCMap<MerkleEntityAssociation, MerkleTokenRelStatus> rels;

	private BackingTokenRels subject;

	@BeforeEach
	private void setup() {
		rels = new FCMap<>();
		rels.put(aKey, aValue);
		rels.put(bKey, bValue);

		subject = new BackingTokenRels(() -> rels);
	}

	@Test
	public void delegatesPutForNewRelIfMissing() {
		// when:
		subject.createRelationship(c, ct, cValue);

		// then:
		assertEquals(cValue, rels.get(fromAccountTokenRel(c, ct)));
		// and:
		assertTrue(subject.existingRels.contains(asKey(c, ct)));
	}

	@Test
	public void delegatesPutForNewRel() {
		// when:
		subject.createRelationship(c, ct, cValue);

		// then:
		assertEquals(cValue, rels.get(fromAccountTokenRel(c, ct)));
	}

	@Test
	public void throwsOnReplacingUnsafeRef() {
		// when:
		assertThrows(IllegalArgumentException.class, () -> subject.createRelationship(a, at, aValue));
	}

	@Test
	public void removeUpdatesBothCacheAndDelegate() {
		// when:
		subject.endRelationship(a, at);

		// then:
		assertFalse(rels.containsKey(fromAccountTokenRel(a, at)));
		// and:
		assertFalse(subject.existingRels.contains(asKey(a, at)));
	}

	@Test
	public void replacesAllMutableRefs() {
		setupMocked();

		given(rels.getForModify(fromAccountTokenRel(a, at))).willReturn(aValue);
		given(rels.getForModify(fromAccountTokenRel(b, bt))).willReturn(bValue);

		// when:
		subject.getRelationshipStatus(a, at);
		subject.getRelationshipStatus(b, bt);
		// and:
		subject.flushMutableRefs();

		// then:
		verify(rels).replace(fromAccountTokenRel(a, at), aValue);
		verify(rels).replace(fromAccountTokenRel(b, bt), bValue);
		// and:
		assertTrue(subject.cache.isEmpty());
	}

	@Test
	public void syncsFromInjectedMap() {
		// expect:
		assertTrue(subject.existingRels.contains(asKey(a, at)));
		assertTrue(subject.existingRels.contains(asKey(b, bt)));
	}

	@Test
	public void containsWorks() {
		// expect:
		assertTrue(subject.inRelationship(a, at));
		assertTrue(subject.inRelationship(b, bt));
	}

	@Test
	public void getIsReadThrough() {
		setupMocked();

		given(rels.getForModify(aKey)).willReturn(aValue);

		// when:
		var firstStatus = subject.getRelationshipStatus(a, at);
		var secondStatus = subject.getRelationshipStatus(a, at);

		// then:
		assertSame(aValue, firstStatus);
		assertSame(aValue, secondStatus);
		// and:
		assertSame(aValue, subject.cache.get(asKey(a, at)));
		// and:
		verify(rels, times(1)).getForModify(any());
	}

	private void setupMocked() {
		rels = mock(FCMap.class);
		given(rels.keySet()).willReturn(Collections.emptySet());
		subject = new BackingTokenRels(() -> rels);
	}
}