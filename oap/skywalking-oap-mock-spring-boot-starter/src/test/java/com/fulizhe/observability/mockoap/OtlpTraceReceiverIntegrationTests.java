package com.fulizhe.observability.mockoap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import org.apache.skywalking.apm.dependencies.io.grpc.stub.StreamObserver;
import org.apache.skywalking.apm.network.common.v3.Commands;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.TraceSegmentReportServiceGrpc;

import com.fulizhe.observability.mockoap.jvmmetrics.JVMMetricBuffer;
import com.fulizhe.observability.mockoap.log.LogBuffer;
import com.fulizhe.observability.mockoap.otlp.OtlpTraceConverter;
import com.fulizhe.observability.mockoap.otlp.OtlpTraceServiceBinding;
import com.fulizhe.observability.mockoap.otlp.OtlpTraceServiceImpl;
import com.fulizhe.observability.mockoap.trace.TraceGraphAssembler;
import com.fulizhe.observability.mockoap.trace.TraceSegmentBuffer;
import com.google.protobuf.ByteString;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;

public class OtlpTraceReceiverIntegrationTests {

	@SuppressWarnings("unchecked")
	@Test
	public void standardOtlpExportIsQueryableAndBuildsParentChildGraph() throws Exception {
		MockOapProperties properties = new MockOapProperties();
		TraceSegmentBuffer traceBuffer = new TraceSegmentBuffer(properties);
		LogBuffer logBuffer = new LogBuffer(properties);
		JVMMetricBuffer jvmMetricBuffer = new JVMMetricBuffer(properties);
		OtlpTraceConverter converter = new OtlpTraceConverter(properties);
		OtlpTraceServiceImpl otlpService = new OtlpTraceServiceImpl(traceBuffer, converter, properties);
		properties.setMaxOtlpSpansPerRequest(100);
		AgentShadedGrpcLauncher launcher = new AgentShadedGrpcLauncher(0, traceBuffer, logBuffer, jvmMetricBuffer,
				properties.getMaxOtlpInboundMessageBytes(),
				new OtlpTraceServiceBinding(otlpService).definition());
		launcher.start();
		ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", launcher.getPort()).usePlaintext().build();
		org.apache.skywalking.apm.dependencies.io.grpc.ManagedChannel nativeChannel =
				org.apache.skywalking.apm.dependencies.io.grpc.ManagedChannelBuilder.forAddress("127.0.0.1",
						launcher.getPort()).usePlaintext().build();
		try {
			TraceServiceGrpc.newBlockingStub(channel).export(request());
			ExportTraceServiceResponse mergeResponse = TraceServiceGrpc.newBlockingStub(channel)
					.export(requestWithAdditionalSpan());
			assertTrue(otlpService.awaitIdle(5000L));
			assertEquals(0L, mergeResponse.getPartialSuccess().getRejectedSpans());

			Map<String, Object> graph = TraceGraphAssembler.assemble("0123456789abcdef0123456789abcdef",
					traceBuffer.findByTraceId("0123456789abcdef0123456789abcdef"));
			assertEquals(3, graph.get("spanCount"));
			List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
			assertEquals("0123456789abcdef", nodes.get(0).get("spanId"));
			assertEquals("fedcba9876543210", nodes.get(1).get("spanId"));
			assertEquals(nodes.get(0).get("key"), nodes.get(1).get("parentKey"));
			assertEquals("1111111111111111", nodes.get(2).get("spanId"));
			assertEquals(nodes.get(0).get("key"), nodes.get(2).get("parentKey"));
			assertEquals("OTLP", nodes.get(0).get("source"));
			assertEquals("200", ((Map<String, String>) nodes.get(0).get("tags")).get("http.response.status_code"));
			assertEquals("cpp-trace-service", nodes.get(0).get("scopeName"));
			assertTrue(((Map<String, Object>) nodes.get(0).get("resourceAttributes")).containsKey("service.instance.id"));
			assertEquals(1, ((List<?>) nodes.get(0).get("events")).size());
			assertEquals("STATUS_CODE_OK", nodes.get(0).get("statusCode"));
			assertEquals("root-status", nodes.get(0).get("statusMessage"));
			assertEquals("1.0.0", nodes.get(0).get("scopeVersion"));
			assertEquals("application", ((Map<String, Object>) nodes.get(0).get("scopeAttributes")).get("domain"));
			assertEquals("0123456789abcdef0123456789abcdef", nodes.get(0).get("traceId"));
			assertEquals(1, traceBuffer.size());
			assertEquals(0L, traceBuffer.receivedTotal());
			assertEquals(1L, traceBuffer.otlpReceivedRecords());
			assertEquals(3L, otlpService.acceptedSpanTotal());

			ExportTraceServiceResponse invalidResponse = TraceServiceGrpc.newBlockingStub(channel)
					.export(requestWithInvalidAndValidSpan());
			assertTrue(otlpService.awaitIdle(5000L));
			assertEquals(0L, invalidResponse.getPartialSuccess().getRejectedSpans());
			assertEquals(1L, otlpService.rejectedSpanTotal());
			assertEquals(4L, otlpService.acceptedSpanTotal());

			properties.setMaxOtlpSpansPerRequest(1);
			ExportTraceServiceResponse oversizedResponse = TraceServiceGrpc.newBlockingStub(channel)
					.export(requestWithTwoValidSpans());
			assertTrue(otlpService.awaitIdle(5000L));
			assertEquals(0L, oversizedResponse.getPartialSuccess().getRejectedSpans());
			assertEquals(2L, otlpService.rejectedSpanTotal());

			properties.setMaxOtlpSpansPerRequest(100);
			properties.setMaxOtlpAttributesPerSpan(0);
			ExportTraceServiceResponse attributeLimitedResponse = TraceServiceGrpc.newBlockingStub(channel)
					.export(request());
			assertTrue(otlpService.awaitIdle(5000L));
			assertEquals(0L, attributeLimitedResponse.getPartialSuccess().getRejectedSpans());
			assertEquals(3L, otlpService.rejectedSpanTotal());

			ExportTraceServiceResponse fullyRejectedResponse = TraceServiceGrpc.newBlockingStub(channel)
					.export(requestWithOnlyInvalidSpan());
			assertTrue(otlpService.awaitIdle(5000L));
			assertEquals(0L, fullyRejectedResponse.getPartialSuccess().getRejectedSpans());
			assertEquals(4L, otlpService.rejectedSpanTotal());

			properties.setMaxOtlpAttributesPerSpan(128);
			properties.setMaxOtlpStringLength(3);
			ExportTraceServiceResponse stringLimitedResponse = TraceServiceGrpc.newBlockingStub(channel).export(request());
			assertTrue(otlpService.awaitIdle(5000L));
			assertEquals(0L, stringLimitedResponse.getPartialSuccess().getRejectedSpans());
			assertEquals(6L, otlpService.rejectedSpanTotal());

			properties.setMaxOtlpStringLength(4096);
			ExportTraceServiceResponse missingServiceResponse = TraceServiceGrpc.newBlockingStub(channel)
					.export(requestWithoutService());
			assertTrue(otlpService.awaitIdle(5000L));
			assertEquals(0L, missingServiceResponse.getPartialSuccess().getRejectedSpans());

			properties.setMaxOtlpStringLength(8);
			ExportTraceServiceResponse badEnvelopeResponse = TraceServiceGrpc.newBlockingStub(channel)
					.export(requestWithOversizedResourceAttribute());
			assertTrue(otlpService.awaitIdle(5000L));
			assertEquals(0L, badEnvelopeResponse.getPartialSuccess().getRejectedSpans());

		CountDownLatch nativeResponse = new CountDownLatch(1);
		StreamObserver<SegmentObject> nativeRequest = TraceSegmentReportServiceGrpc.newStub(nativeChannel)
				.collect(new StreamObserver<Commands>() {
					@Override public void onNext(Commands value) { }
					@Override public void onError(Throwable t) { nativeResponse.countDown(); }
					@Override public void onCompleted() { nativeResponse.countDown(); }
				});
		nativeRequest.onNext(SegmentObject.newBuilder().setTraceId("native-trace")
				.setTraceSegmentId("native-segment").setService("java-service").setServiceInstance("java-1")
				.addSpans(SpanObject.newBuilder().setSpanId(7).setParentSpanId(-1)
						.setOperationName("GET /native").setStartTime(1_700_000_000_000L)
						.setEndTime(1_700_000_000_010L).build()).build());
		nativeRequest.onCompleted();
		assertTrue(nativeResponse.await(5, TimeUnit.SECONDS));
		long deadline = System.currentTimeMillis() + 5000L;
		while (traceBuffer.findByTraceId("native-trace").isEmpty() && System.currentTimeMillis() < deadline) {
			Thread.sleep(10L);
		}

			assertEquals(1L, traceBuffer.receivedTotal());
			assertEquals("native-segment", traceBuffer.findByTraceId("native-trace").get(0).getTraceSegmentId());
		} finally {
			channel.shutdownNow();
			channel.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
			nativeChannel.shutdownNow();
			nativeChannel.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
			launcher.stop();
		}
	}

	private static ExportTraceServiceRequest request() {
		Resource resource = Resource.newBuilder()
				.addAttributes(attribute("service.name", "cpp-service"))
				.addAttributes(attribute("service.instance.id", "cpp-instance-1"))
				.build();
		Span root = Span.newBuilder()
				.setTraceId(ByteString.copyFrom(hex("0123456789abcdef0123456789abcdef")))
				.setSpanId(ByteString.copyFrom(hex("0123456789abcdef")))
				.setName("GET /api/work")
				.setKind(Span.SpanKind.SPAN_KIND_SERVER)
				.setStartTimeUnixNano(1_700_000_000_000_000_000L)
				.setEndTimeUnixNano(1_700_000_000_100_000_000L)
				.addAttributes(attribute("http.response.status_code", 200))
				.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).setMessage("root-status"))
				.addEvents(Span.Event.newBuilder().setName("response sent")
						.setTimeUnixNano(1_700_000_000_090_000_000L)
						.addAttributes(attribute("event.kind", "response")).build())
				.addLinks(Span.Link.newBuilder()
						.setTraceId(ByteString.copyFrom(hex("0123456789abcdef0123456789abcdef")))
						.setSpanId(ByteString.copyFrom(hex("1111111111111111"))).setTraceState("vendor=value"))
				.build();
		Span child = Span.newBuilder()
				.setTraceId(ByteString.copyFrom(hex("0123456789abcdef0123456789abcdef")))
				.setSpanId(ByteString.copyFrom(hex("fedcba9876543210")))
				.setParentSpanId(ByteString.copyFrom(hex("0123456789abcdef")))
				.setName("SELECT demo_items")
				.setKind(Span.SpanKind.SPAN_KIND_CLIENT)
				.setStartTimeUnixNano(1_700_000_000_010_000_000L)
				.setEndTimeUnixNano(1_700_000_000_020_000_000L)
				.build();
		return ExportTraceServiceRequest.newBuilder().addResourceSpans(ResourceSpans.newBuilder()
				.setResource(resource)
				.setSchemaUrl("https://example.test/resource-schema")
				.addScopeSpans(ScopeSpans.newBuilder()
						.setScope(InstrumentationScope.newBuilder().setName("cpp-trace-service").setVersion("1.0.0")
								.addAttributes(attribute("domain", "application")))
						.setSchemaUrl("https://example.test/scope-schema")
						.addSpans(root).addSpans(child)))
			.build();
	}

	private static ExportTraceServiceRequest requestWithAdditionalSpan() {
		Span span = Span.newBuilder().setTraceId(ByteString.copyFrom(hex("0123456789abcdef0123456789abcdef")))
				.setSpanId(ByteString.copyFrom(hex("1111111111111111")))
				.setParentSpanId(ByteString.copyFrom(hex("0123456789abcdef"))).setName("cache lookup")
				.setKind(Span.SpanKind.SPAN_KIND_INTERNAL).setStartTimeUnixNano(1_700_000_000_030_000_000L)
				.setEndTimeUnixNano(1_700_000_000_040_000_000L).build();
		Resource resource = Resource.newBuilder().addAttributes(attribute("service.instance.id", "cpp-instance-1"))
				.addAttributes(attribute("service.name", "cpp-service")).build();
		return ExportTraceServiceRequest.newBuilder().addResourceSpans(ResourceSpans.newBuilder().setResource(resource)
				.addScopeSpans(ScopeSpans.newBuilder().addSpans(span))).build();
	}

	private static ExportTraceServiceRequest requestWithInvalidAndValidSpan() {
		Span invalid = Span.newBuilder().setTraceId(ByteString.copyFrom(hex("00000000000000000000000000000000")))
				.setSpanId(ByteString.copyFrom(hex("0000000000000000"))).setName("invalid").build();
		Span valid = Span.newBuilder().setTraceId(ByteString.copyFrom(hex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
				.setSpanId(ByteString.copyFrom(hex("bbbbbbbbbbbbbbbb"))).setName("valid sibling")
				.setStartTimeUnixNano(1_700_000_000_050_000_000L).setEndTimeUnixNano(1_700_000_000_060_000_000L).build();
		Resource resource = Resource.newBuilder().addAttributes(attribute("service.name", "cpp-service")).build();
		return ExportTraceServiceRequest.newBuilder().addResourceSpans(ResourceSpans.newBuilder().setResource(resource)
				.addScopeSpans(ScopeSpans.newBuilder().addSpans(invalid).addSpans(valid))).build();
	}

	private static ExportTraceServiceRequest requestWithTwoValidSpans() {
		Span first = Span.newBuilder().setTraceId(ByteString.copyFrom(hex("cccccccccccccccccccccccccccccccc")))
				.setSpanId(ByteString.copyFrom(hex("dddddddddddddddd"))).setName("first")
				.setStartTimeUnixNano(1_700_000_000_050_000_000L).setEndTimeUnixNano(1_700_000_000_060_000_000L).build();
		Span second = Span.newBuilder().setTraceId(ByteString.copyFrom(hex("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")))
				.setSpanId(ByteString.copyFrom(hex("ffffffffffffffff"))).setName("second")
				.setStartTimeUnixNano(1_700_000_000_070_000_000L).setEndTimeUnixNano(1_700_000_000_080_000_000L).build();
		return ExportTraceServiceRequest.newBuilder().addResourceSpans(ResourceSpans.newBuilder()
				.setResource(Resource.newBuilder().addAttributes(attribute("service.name", "cpp-service")))
				.addScopeSpans(ScopeSpans.newBuilder().addSpans(first).addSpans(second))).build();
	}

	private static ExportTraceServiceRequest requestWithOnlyInvalidSpan() {
		Span invalid = Span.newBuilder().setTraceId(ByteString.copyFrom(hex("00000000000000000000000000000000")))
				.setSpanId(ByteString.copyFrom(hex("0000000000000000"))).setName("invalid").build();
		return ExportTraceServiceRequest.newBuilder().addResourceSpans(ResourceSpans.newBuilder()
				.setResource(Resource.newBuilder().addAttributes(attribute("service.name", "cpp-service")))
				.addScopeSpans(ScopeSpans.newBuilder().addSpans(invalid))).build();
	}

	private static ExportTraceServiceRequest requestWithoutService() {
		Span span = Span.newBuilder().setTraceId(ByteString.copyFrom(hex("cccccccccccccccccccccccccccccccc")))
				.setSpanId(ByteString.copyFrom(hex("dddddddddddddddd"))).setName("no service")
				.setStartTimeUnixNano(1_700_000_000_050_000_000L).setEndTimeUnixNano(1_700_000_000_060_000_000L).build();
		return ExportTraceServiceRequest.newBuilder().addResourceSpans(ResourceSpans.newBuilder()
				.addScopeSpans(ScopeSpans.newBuilder().addSpans(span))).build();
	}

	private static ExportTraceServiceRequest requestWithOversizedResourceAttribute() {
		Span span = Span.newBuilder().setTraceId(ByteString.copyFrom(hex("cccccccccccccccccccccccccccccccc")))
				.setSpanId(ByteString.copyFrom(hex("dddddddddddddddd"))).setName("valid-resource-limit")
				.setStartTimeUnixNano(1_700_000_000_050_000_000L).setEndTimeUnixNano(1_700_000_000_060_000_000L).build();
		return ExportTraceServiceRequest.newBuilder().addResourceSpans(ResourceSpans.newBuilder()
				.setResource(Resource.newBuilder().addAttributes(attribute("service.name", "svc"))
						.addAttributes(attribute("description", "too long for current string limit")))
				.addScopeSpans(ScopeSpans.newBuilder().addSpans(span))).build();
	}

	private static io.opentelemetry.proto.common.v1.KeyValue attribute(String key, String value) {
		return io.opentelemetry.proto.common.v1.KeyValue.newBuilder().setKey(key)
				.setValue(AnyValue.newBuilder().setStringValue(value)).build();
	}

	private static io.opentelemetry.proto.common.v1.KeyValue attribute(String key, long value) {
		return io.opentelemetry.proto.common.v1.KeyValue.newBuilder().setKey(key)
				.setValue(AnyValue.newBuilder().setIntValue(value)).build();
	}

	private static byte[] hex(String hex) {
		byte[] bytes = new byte[hex.length() / 2];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		}
		return bytes;
	}
}
