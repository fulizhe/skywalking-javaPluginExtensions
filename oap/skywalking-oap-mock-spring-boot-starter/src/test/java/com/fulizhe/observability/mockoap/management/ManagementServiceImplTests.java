package com.fulizhe.observability.mockoap.management;

import org.apache.skywalking.apm.network.management.v3.InstancePingPkg;
import org.apache.skywalking.apm.network.management.v3.InstanceProperties;
import org.junit.jupiter.api.Test;

import com.fulizhe.observability.mockoap.testutil.RecordingCommandsObserver;

public class ManagementServiceImplTests {

	@Test
	public void acknowledgesInstancePropertiesWithEmptyCommands() {
		RecordingCommandsObserver observer = new RecordingCommandsObserver();

		new ManagementServiceImpl().reportInstanceProperties(InstanceProperties.newBuilder()
				.setService("demo-service")
				.setServiceInstance("demo-instance")
				.build(), observer);

		observer.assertAcknowledged();
	}

	@Test
	public void acknowledgesKeepAliveWithEmptyCommands() {
		RecordingCommandsObserver observer = new RecordingCommandsObserver();

		new ManagementServiceImpl().keepAlive(InstancePingPkg.newBuilder()
				.setService("demo-service")
				.setServiceInstance("demo-instance")
				.build(), observer);

		observer.assertAcknowledged();
	}
}
