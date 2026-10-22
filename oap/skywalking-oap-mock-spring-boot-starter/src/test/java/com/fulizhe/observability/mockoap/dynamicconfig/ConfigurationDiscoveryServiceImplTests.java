package com.fulizhe.observability.mockoap.dynamicconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.skywalking.apm.dependencies.io.grpc.stub.StreamObserver;
import org.apache.skywalking.apm.network.common.v3.Commands;
import org.apache.skywalking.apm.network.language.agent.v3.ConfigurationSyncRequest;
import org.junit.jupiter.api.Test;

public class ConfigurationDiscoveryServiceImplTests {

	@Test
	public void returnsEmptyCommandsWhenNoDynamicConfigurationExists() {
		final Commands[] response = new Commands[1];
		final boolean[] completed = new boolean[1];
		final Throwable[] error = new Throwable[1];

		new ConfigurationDiscoveryServiceImpl().fetchConfigurations(ConfigurationSyncRequest.getDefaultInstance(),
				new StreamObserver<Commands>() {
					@Override
					public void onNext(Commands commands) {
						response[0] = commands;
					}

					@Override
					public void onError(Throwable throwable) {
						error[0] = throwable;
					}

					@Override
					public void onCompleted() {
						completed[0] = true;
					}
				});

		assertEquals(Commands.getDefaultInstance(), response[0]);
		assertEquals(0, response[0].getCommandsCount());
		assertTrue(completed[0]);
		assertNull(error[0]);
	}
}
