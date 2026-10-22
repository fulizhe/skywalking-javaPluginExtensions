package com.fulizhe.observability.mockoap.jvmmetrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.skywalking.apm.dependencies.io.grpc.stub.StreamObserver;
import org.apache.skywalking.apm.network.common.v3.Commands;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetricCollection;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetric;
import org.apache.skywalking.apm.network.language.agent.v3.Memory;
import org.junit.jupiter.api.Test;

import com.fulizhe.observability.mockoap.MockOapProperties;

public class JVMMetricReportServiceImplTests {

	@Test
	public void acknowledgesJvmMetricCollectionWithEmptyCommands() throws Exception {
		JVMMetricBuffer buffer = new JVMMetricBuffer(new MockOapProperties());
		final Commands[] response = new Commands[1];
		final boolean[] completed = new boolean[1];
		final Throwable[] error = new Throwable[1];

		JVMMetricReportServiceImpl service = new JVMMetricReportServiceImpl(buffer);
		service.collect(JVMMetricCollection.getDefaultInstance(), new StreamObserver<Commands>() {
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
		assertTrue(service.awaitIdle(2000L));
		assertEquals(1, buffer.size());
		assertEquals(1L, buffer.receivedTotal());
		service.close();
	}

	@Test
	public void rejectedIngestDoesNotAcknowledgeSuccess() {
		JVMMetricReportServiceImpl service = new JVMMetricReportServiceImpl(
				new JVMMetricBuffer(new MockOapProperties()));
		service.close();
		final boolean[] completed = new boolean[1];
		final Throwable[] error = new Throwable[1];

		service.collect(JVMMetricCollection.getDefaultInstance(), new StreamObserver<Commands>() {
			@Override
			public void onNext(Commands commands) {
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

		assertNotNull(error[0]);
		assertFalse(completed[0]);
	}

	@Test
	public void convertsJvmMetricFieldsIntoCachedSnapshot() {
		JVMMetricBuffer buffer = new JVMMetricBuffer(new MockOapProperties());
		JVMMetricCollection collection = JVMMetricCollection.newBuilder()
				.setService("service-a")
				.setServiceInstance("instance-a")
				.addMetrics(JVMMetric.newBuilder()
						.setTime(1234L)
						.addMemory(Memory.newBuilder().setIsHeap(true).setInit(10L).setMax(100L)
								.setUsed(40L).setCommitted(80L)))
				.build();

		JVMMetricReportServiceImpl service = new JVMMetricReportServiceImpl(buffer);
		MockOapJvmMetricRecord record = service.toRecord(collection);
		service.close();
		buffer.add(record);

		assertEquals("service-a", buffer.snapshot(1).get(0).getService());
		assertEquals("instance-a", record.getServiceInstance());
		assertEquals(1234L, record.getMetrics().get(0).get("time"));
		@SuppressWarnings("unchecked")
		java.util.List<java.util.Map<String, Object>> memory =
				(java.util.List<java.util.Map<String, Object>>) record.getMetrics().get(0).get("memory");
		assertEquals(Boolean.TRUE, memory.get(0).get("isHeap"));
		assertEquals(40L, memory.get(0).get("used"));
	}
}
