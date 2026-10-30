package com.fulizhe.observability.mockoap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.skywalking.apm.dependencies.io.grpc.ManagedChannel;
import org.apache.skywalking.apm.dependencies.io.grpc.ManagedChannelBuilder;
import org.apache.skywalking.apm.dependencies.io.grpc.stub.StreamObserver;
import org.apache.skywalking.apm.network.common.v3.Commands;
import org.apache.skywalking.apm.network.language.agent.v3.MeterDataCollection;
import org.apache.skywalking.apm.network.language.agent.v3.MeterReportServiceGrpc;
import org.apache.skywalking.apm.network.management.v3.InstancePingPkg;
import org.apache.skywalking.apm.network.management.v3.InstanceProperties;
import org.apache.skywalking.apm.network.management.v3.ManagementServiceGrpc;
import org.junit.jupiter.api.Test;

import com.fulizhe.observability.mockoap.jvmmetrics.JVMMetricBuffer;
import com.fulizhe.observability.mockoap.log.LogBuffer;
import com.fulizhe.observability.mockoap.trace.TraceSegmentBuffer;

/**
 * 验证 collector 真正注册了 Agent 注册/心跳与 meter 服务，而不是让客户端收到 {@code UNIMPLEMENTED}。
 */
public class AgentShadedGrpcServiceRegistrationTests {

	@Test
	public void servesManagementAndMeterServices() throws Exception {
		MockOapProperties properties = new MockOapProperties();
		AgentShadedGrpcLauncher launcher = new AgentShadedGrpcLauncher(0,
				new TraceSegmentBuffer(properties), new LogBuffer(properties), new JVMMetricBuffer(properties));
		launcher.start();
		ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", launcher.getPort())
				.usePlaintext()
				.build();
		try {
			Commands registered = ManagementServiceGrpc.newBlockingStub(channel)
					.withDeadlineAfter(2, TimeUnit.SECONDS)
					.reportInstanceProperties(InstanceProperties.newBuilder()
							.setService("demo-service")
							.setServiceInstance("demo-instance")
							.build());
			assertEquals(0, registered.getCommandsCount());

			Commands alive = ManagementServiceGrpc.newBlockingStub(channel)
					.withDeadlineAfter(2, TimeUnit.SECONDS)
					.keepAlive(InstancePingPkg.newBuilder()
							.setService("demo-service")
							.setServiceInstance("demo-instance")
							.build());
			assertEquals(0, alive.getCommandsCount());

			CountDownLatch meterDone = new CountDownLatch(1);
			AtomicReference<Commands> meterResponse = new AtomicReference<Commands>();
			AtomicReference<Throwable> meterError = new AtomicReference<Throwable>();
			StreamObserver<MeterDataCollection> meterRequest = MeterReportServiceGrpc.newStub(channel)
					.withDeadlineAfter(2, TimeUnit.SECONDS)
					.collectBatch(new StreamObserver<Commands>() {
						@Override
						public void onNext(Commands commands) {
							meterResponse.set(commands);
						}

						@Override
						public void onError(Throwable throwable) {
							meterError.set(throwable);
							meterDone.countDown();
						}

						@Override
						public void onCompleted() {
							meterDone.countDown();
						}
					});
			meterRequest.onNext(MeterDataCollection.getDefaultInstance());
			meterRequest.onCompleted();

			assertTrue(meterDone.await(2, TimeUnit.SECONDS), "meter response should arrive");
			assertNull(meterError.get());
			assertNotNull(meterResponse.get(), "meter response");
			assertEquals(0, meterResponse.get().getCommandsCount());
		} finally {
			channel.shutdownNow();
			channel.awaitTermination(2, TimeUnit.SECONDS);
			launcher.stop();
		}
	}
}
