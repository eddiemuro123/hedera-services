package com.hedera.services.grpc;

import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.context.properties.Profile;
import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.hedera.test.extensions.LoggingTarget;
import com.swirlds.common.Address;
import com.swirlds.common.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintStream;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;


@ExtendWith({ MockitoExtension.class, LogCaptureExtension.class })
class GrpcStarterTest {
	private final int port = 50211;
	private final int tlsPort = 50212;
	private final NodeId nodeId = new NodeId(false, 123L);

	@Mock
	private Address nodeAddress;
	@Mock
	private GrpcServerManager grpcServerManager;
	@Mock
	private NodeLocalProperties nodeLocalProperties;
	@Mock
	private PrintStream console;

	@LoggingTarget
	private LogCaptor logCaptor;
	@LoggingSubject
	private GrpcStarter subject;

	@BeforeEach
	void setUp() {
		given(nodeLocalProperties.port()).willReturn(port);
		given(nodeLocalProperties.tlsPort()).willReturn(tlsPort);

		subject = new GrpcStarter(nodeId, nodeAddress, grpcServerManager, nodeLocalProperties, Optional.of(console));
	}

	@Test
	void startsUnconditionallyWithProdProfile() {
		given(nodeLocalProperties.activeProfile()).willReturn(Profile.PROD);

		// when:
		subject.startIfAppropriate();

		// then:
		verify(grpcServerManager).start(intThat(i -> i == port), intThat(j -> j == tlsPort), any());
		// and:
		assertThat(logCaptor.infoLogs(), contains(
				equalTo("TLS is turned on by default on node 123"),
				equalTo("Active profile: PROD")));
	}

	@Test
	void weirdlyJustWarnsOnTestProfile() {
		given(nodeLocalProperties.activeProfile()).willReturn(Profile.TEST);

		// when:
		subject.startIfAppropriate();

		// then:
		verifyNoInteractions(grpcServerManager);
		// and:
		assertThat(logCaptor.warnLogs(), contains(
				equalTo("No Netty config for profile TEST, skipping gRPC startup")));
	}

	@Test
	void startsIfBlessedOnDevProfileOnlyOneNodeListening() {
		given(nodeLocalProperties.activeProfile()).willReturn(Profile.DEV);
		given(nodeLocalProperties.devOnlyDefaultNodeListens()).willReturn(true);
		given(nodeAddress.getMemo()).willReturn("0.0.3");
		given(nodeLocalProperties.devListeningAccount()).willReturn("0.0.3");

		// when:
		subject.startIfAppropriate();

		// then:
		verify(grpcServerManager).start(intThat(i -> i == port), intThat(j -> j == tlsPort), any());
	}

	@Test
	void doesntStartIfNotBlessedOnDevProfileOnlyOneNodeListening() {
		given(nodeLocalProperties.activeProfile()).willReturn(Profile.DEV);
		given(nodeLocalProperties.devOnlyDefaultNodeListens()).willReturn(true);
		given(nodeAddress.getMemo()).willReturn("0.0.4");
		given(nodeLocalProperties.devListeningAccount()).willReturn("0.0.3");

		// when:
		subject.startIfAppropriate();

		// then:
		verifyNoInteractions(grpcServerManager);
	}

	@Test
	void startsIfBlessedOnDevProfileAllNodesListening() {
		given(nodeLocalProperties.activeProfile()).willReturn(Profile.DEV);
		given(nodeAddress.getMemo()).willReturn("0.0.3");
		given(nodeLocalProperties.devListeningAccount()).willReturn("0.0.3");

		// when:
		subject.startIfAppropriate();

		// then:
		verify(grpcServerManager).start(intThat(i -> i == port), intThat(j -> j == tlsPort), any());
	}

	@Test
	void startsIfUnblessedOnDevProfileAllNodesListening() {
		given(nodeLocalProperties.activeProfile()).willReturn(Profile.DEV);
		given(nodeAddress.getMemo()).willReturn("0.0.4");
		given(nodeAddress.getPortExternalIpv4()).willReturn(50666);
		given(nodeLocalProperties.devListeningAccount()).willReturn("0.0.3");

		// when:
		subject.startIfAppropriate();

		// then:
		verify(grpcServerManager).start(intThat(i -> i == port + 666), intThat(j -> j == tlsPort + 666), any());
	}
}