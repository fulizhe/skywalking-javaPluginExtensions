package com.fulizhe.observability.mockoap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.skywalking.apm.dependencies.io.grpc.CallOptions;
import org.apache.skywalking.apm.dependencies.io.grpc.ManagedChannel;
import org.apache.skywalking.apm.dependencies.io.grpc.ManagedChannelBuilder;
import org.apache.skywalking.apm.dependencies.io.grpc.Metadata;
import org.apache.skywalking.apm.dependencies.io.grpc.MethodDescriptor;
import org.apache.skywalking.apm.dependencies.io.grpc.ServerCall;
import org.apache.skywalking.apm.dependencies.io.grpc.ServerCallHandler;
import org.apache.skywalking.apm.dependencies.io.grpc.ServerServiceDefinition;
import org.apache.skywalking.apm.dependencies.io.grpc.Status;
import org.apache.skywalking.apm.dependencies.io.grpc.stub.ClientCalls;
import org.junit.jupiter.api.Test;

import com.fulizhe.observability.mockoap.jvmmetrics.JVMMetricBuffer;
import com.fulizhe.observability.mockoap.log.LogBuffer;
import com.fulizhe.observability.mockoap.trace.TraceSegmentBuffer;

public class AgentShadedGrpcLauncherShutdownTests {

	private static final MethodDescriptor<String, String> SLOW_METHOD = MethodDescriptor
			.<String, String>newBuilder()
			.setType(MethodDescriptor.MethodType.UNARY)
			.setFullMethodName("test.SlowService/Call")
			.setRequestMarshaller(new StringMarshaller())
			.setResponseMarshaller(new StringMarshaller())
			.build();

	@Test
	public void stopWaitsForInFlightCallToFinish() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ServerServiceDefinition slowService = ServerServiceDefinition.builder("test.SlowService")
				.addMethod(SLOW_METHOD, new ServerCallHandler<String, String>() {
					@Override
					public ServerCall.Listener<String> startCall(final ServerCall<String, String> call,
							Metadata headers) {
						call.request(2);
						return new ServerCall.Listener<String>() {
							@Override
							public void onMessage(String message) {
								// no-op
							}

							@Override
							public void onHalfClose() {
								entered.countDown();
								try {
									release.await(5, TimeUnit.SECONDS);
								} catch (InterruptedException e) {
									Thread.currentThread().interrupt();
								}
								call.sendHeaders(new Metadata());
								call.sendMessage("pong");
								call.close(Status.OK, new Metadata());
							}

							@Override
							public void onCancel() {
								release.countDown();
							}
						};
					}
				})
				.build();

		MockOapProperties properties = new MockOapProperties();
		AgentShadedGrpcLauncher launcher = new AgentShadedGrpcLauncher(0, new TraceSegmentBuffer(properties),
				new LogBuffer(properties), new JVMMetricBuffer(properties), 1024 * 1024, slowService);
		launcher.start();
		ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", launcher.getPort()).usePlaintext().build();
		ExecutorService callExecutor = Executors.newSingleThreadExecutor();
		Thread stopper = null;
		try {
			Future<String> response = callExecutor.submit(() -> ClientCalls.blockingUnaryCall(channel, SLOW_METHOD,
					CallOptions.DEFAULT, "ping"));
			assertTrue(entered.await(2, TimeUnit.SECONDS), "server should receive the call");

			stopper = new Thread(launcher::stop);
			stopper.start();
			long deadline = System.currentTimeMillis() + 2000L;
			while (launcher.isRunning() && System.currentTimeMillis() < deadline) {
				Thread.sleep(10L);
			}
			assertFalse(launcher.isRunning(), "server should stop accepting calls");
			assertTrue(stopper.isAlive(), "stop() should wait for the in-flight call instead of cancelling it");

			release.countDown();
			assertEquals("pong", response.get(2, TimeUnit.SECONDS));
			stopper.join(3000L);
			assertFalse(stopper.isAlive(), "stop() should finish after the in-flight call drains");
		} finally {
			release.countDown();
			if (stopper != null) {
				stopper.join(3000L);
			}
			channel.shutdownNow();
			channel.awaitTermination(2, TimeUnit.SECONDS);
			launcher.stop();
			callExecutor.shutdownNow();
		}
	}

	private static final class StringMarshaller implements MethodDescriptor.Marshaller<String> {

		@Override
		public InputStream stream(String value) {
			return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public String parse(InputStream stream) {
			try {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buffer = new byte[256];
				int count;
				while ((count = stream.read(buffer)) != -1) {
					out.write(buffer, 0, count);
				}
				return new String(out.toByteArray(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new IllegalArgumentException("Unable to read test payload", e);
			}
		}
	}
}
