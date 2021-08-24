package com.hedera.services;

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

import com.hedera.services.context.CurrentPlatformStatus;
import com.hedera.services.context.init.ServicesInitFlow;
import com.hedera.services.context.properties.BootstrapProperties;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.grpc.NettyGrpcServerManager;
import com.hedera.services.ledger.accounts.BackingAccounts;
import com.hedera.services.sigs.ExpansionHelper;
import com.hedera.services.sigs.order.SigRequirements;
import com.hedera.services.state.DualStateAccessor;
import com.hedera.services.state.StateAccessor;
import com.hedera.services.state.exports.SignedStateBalancesExporter;
import com.hedera.services.state.exports.ToStringAccountsExporter;
import com.hedera.services.state.forensics.HashLogger;
import com.hedera.services.state.forensics.IssListener;
import com.hedera.services.state.initialization.HfsSystemFilesManager;
import com.hedera.services.state.logic.NetworkCtxManager;
import com.hedera.services.state.logic.StandardProcessLogic;
import com.hedera.services.state.validation.BasedLedgerValidator;
import com.hedera.services.stats.ServicesStatsManager;
import com.hedera.services.stream.RecordStreamManager;
import com.hedera.services.stream.RecordsRunningHashLeaf;
import com.hedera.services.txns.span.ExpandHandleSpan;
import com.hedera.services.utils.JvmSystemExits;
import com.swirlds.common.Address;
import com.swirlds.common.AddressBook;
import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.crypto.RunningHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.hedera.services.context.ServicesNodeType.STAKED_NODE;
import static com.hedera.services.utils.SleepingPause.SLEEPING_PAUSE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ServicesAppTest {
	private final long selfId = 123;
	private final NodeId selfNodeId = new NodeId(false, selfId);

	@Mock
	private Hash hash;
	@Mock
	private Platform platform;
	@Mock
	private RunningHash runningHash;
	@Mock
	private Address address;
	@Mock
	private AddressBook addressBook;
	@Mock
	private Cryptography cryptography;
	@Mock
	private ServicesState initialState;
	@Mock
	private RecordsRunningHashLeaf runningHashLeaf;

	private ServicesApp subject;

	@BeforeEach
	void setUp() {
		// setup:
		final var bootstrapProps = new BootstrapProperties();

		given(address.getStake()).willReturn(123_456_789L);
		given(addressBook.getAddress(selfId)).willReturn(address);
		given(initialState.addressBook()).willReturn(addressBook);
		given(initialState.runningHashLeaf()).willReturn(runningHashLeaf);
		given(runningHashLeaf.getRunningHash()).willReturn(runningHash);
		given(runningHash.getHash()).willReturn(hash);
		given(platform.getCryptography()).willReturn(cryptography);
		given(platform.getSelfId()).willReturn(selfNodeId);

		subject = DaggerServicesApp.builder()
				.bootstrapProps(bootstrapProps)
				.initialState(initialState)
				.platform(platform)
				.selfId(selfId)
				.build();
	}

	@Test
	void objectGraphRootsAreAvailable() {
		// expect:
		assertThat(subject.logic(), instanceOf(StandardProcessLogic.class));
		assertThat(subject.hashLogger(), instanceOf(HashLogger.class));
		assertThat(subject.workingState(), instanceOf(StateAccessor.class));
		assertThat(subject.expansionHelper(), instanceOf(ExpansionHelper.class));
		assertThat(subject.retryingSigReqs(), instanceOf(SigRequirements.class));
		assertThat(subject.expandHandleSpan(), instanceOf(ExpandHandleSpan.class));
		assertThat(subject.dualStateAccessor(), instanceOf(DualStateAccessor.class));
		assertThat(subject.initializationFlow(), instanceOf(ServicesInitFlow.class));
		assertThat(subject.nodeLocalProperties(), instanceOf(NodeLocalProperties.class));
		assertThat(subject.recordStreamManager(), instanceOf(RecordStreamManager.class));
		assertThat(subject.globalDynamicProperties(), instanceOf(GlobalDynamicProperties.class));
		// and:
		assertThat(subject.grpc(), instanceOf(NettyGrpcServerManager.class));
		assertThat(subject.platformStatus(), instanceOf(CurrentPlatformStatus.class));
		assertThat(subject.accountsExporter(), instanceOf(ToStringAccountsExporter.class));
		assertThat(subject.balancesExporter(), instanceOf(SignedStateBalancesExporter.class));
		assertThat(subject.networkCtxManager(), instanceOf(NetworkCtxManager.class));
		assertThat(subject.sysFilesManager(), instanceOf(HfsSystemFilesManager.class));
		assertThat(subject.backingAccounts(), instanceOf(BackingAccounts.class));
		assertThat(subject.statsManager(), instanceOf(ServicesStatsManager.class));
		assertThat(subject.issListener(), instanceOf(IssListener.class));
		assertThat(subject.ledgerValidator(), instanceOf(BasedLedgerValidator.class));
		assertThat(subject.systemExits(), instanceOf(JvmSystemExits.class));
		// and:
		assertSame(subject.nodeId(), selfNodeId);
		assertSame(subject.pause(), SLEEPING_PAUSE);
		assertTrue(subject.consoleOut().isEmpty());
		assertSame(subject.nodeAddress(), address);
		assertEquals(STAKED_NODE, subject.nodeType());
	}
}
