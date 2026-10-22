package com.fulizhe.observability.mockoap.otlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fulizhe.observability.mockoap.MockOapProperties;
import com.fulizhe.observability.mockoap.trace.MockOapSpan;
import com.fulizhe.observability.mockoap.trace.MockOapTraceRecord;
import com.fulizhe.observability.mockoap.trace.TraceSegmentBuffer;
import com.google.protobuf.ByteString;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;

public class OtlpTraceConverterTests {

	@Test
	public void repeatedExportsMergeBySpanIdAndKeepNativeCountersSeparate() {
		MockOapProperties properties = new MockOapProperties();
		properties.setMaxTraces(2);
		TraceSegmentBuffer buffer = new TraceSegmentBuffer(properties);
		OtlpTraceConverter converter = new OtlpTraceConverter(properties);
		OtlpTraceServiceImpl service = new OtlpTraceServiceImpl(buffer, converter, properties);
		Resource resource = Resource.newBuilder().addAttributes(stringAttribute("service.name", "svc")).build();
		Span original = span("0123456789abcdef0123456789abcdef", "0123456789abcdef", null, 10, 20)
				.toBuilder().setName("original").build();
		Span extra = span("0123456789abcdef0123456789abcdef", "fedcba9876543210", "0123456789abcdef", 20, 30);
		service.export(request(resource, original));
		service.export(request(resource, extra));
		service.export(request(resource, original.toBuilder().setName("updated").build()));

		MockOapTraceRecord merged = buffer.findByTraceId("0123456789abcdef0123456789abcdef").get(0);
		assertEquals(2, merged.getSpans().size());
		assertEquals("updated", merged.getSpans().get(0).getOperationName());
		assertEquals(2, merged.getSpanCount());
		assertEquals(1L, buffer.otlpReceivedRecords());
		assertEquals(0L, buffer.receivedTotal());
		assertEquals(3L, service.acceptedSpanTotal());

		MockOapTraceRecord nativeRecord = new MockOapTraceRecord();
		nativeRecord.setTraceSegmentId("native-segment");
		nativeRecord.setTraceId("native-trace");
		buffer.add(nativeRecord);
		assertEquals(1L, buffer.receivedTotal());
	}

	@Test
	public void recordBufferEvictsOldestRecordWhenCapacityIsReached() {
		MockOapProperties properties = new MockOapProperties();
		properties.setMaxTraces(2);
		TraceSegmentBuffer buffer = new TraceSegmentBuffer(properties);
		buffer.mergeOtlp(record("trace-one", "record-one", "1111111111111111"));
		buffer.mergeOtlp(record("trace-two", "record-two", "2222222222222222"));
		buffer.mergeOtlp(record("trace-three", "record-three", "3333333333333333"));
		assertEquals(2, buffer.size());
		assertTrue(buffer.findByTraceId("trace-one").isEmpty());
		assertEquals(1, buffer.findByTraceId("trace-two").size());
		assertEquals(1, buffer.findByTraceId("trace-three").size());
	}

	@Test
	public void validatesIdsTimestampsAndLinkTargets() {
		OtlpTraceConverter converter = new OtlpTraceConverter(new MockOapProperties());
		Span valid = span("0123456789abcdef0123456789abcdef", "0123456789abcdef", null, 10, 20);
		assertTrue(converter.isValidSpan(valid));
		assertFalse(converter.isValidSpan(span("0123", "0123456789abcdef", null, 10, 20)));
		assertFalse(converter.isValidSpan(span("00000000000000000000000000000000", "0123456789abcdef", null, 10, 20)));
		assertFalse(converter.isValidSpan(span("0123456789abcdef0123456789abcdef", "0000000000000000", null, 10, 20)));
		assertFalse(converter.isValidSpan(span("0123456789abcdef0123456789abcdef", "0123456789abcdef",
				"0000000000000000", 10, 20)));
		Span badLink = valid.toBuilder().addLinks(Span.Link.newBuilder()
				.setTraceId(ByteString.copyFrom(new byte[16]))
				.setSpanId(ByteString.copyFrom(hex("0123456789abcdef")))).build();
		assertFalse(converter.isValidSpan(badLink));
	}

	@Test
	public void resolvesServiceFallbackAndRejectsMissingService() {
		OtlpTraceConverter converter = new OtlpTraceConverter(new MockOapProperties());
		ResourceSpans fallback = resource("faas.name", "worker", span("0123456789abcdef0123456789abcdef",
				"0123456789abcdef", null, 1, 2));
		List<MockOapTraceRecord> records = converter.toRecords(fallback);
		assertEquals(1, records.size());
		assertEquals("worker", records.get(0).getService());
		ResourceSpans blankPreferred = ResourceSpans.newBuilder().setResource(Resource.newBuilder()
				.addAttributes(stringAttribute("service.name", " "))
				.addAttributes(stringAttribute("faas.name", "fallback-service")))
				.addScopeSpans(ScopeSpans.newBuilder().addSpans(span("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
						"aaaaaaaaaaaaaaaa", null, 1, 2))).build();
		assertEquals("fallback-service", converter.toRecords(blankPreferred).get(0).getService());

		ResourceSpans missing = ResourceSpans.newBuilder().addScopeSpans(ScopeSpans.newBuilder()
				.addSpans(span("0123456789abcdef0123456789abcdef", "0123456789abcdef", null, 1, 2))).build();
		assertTrue(converter.toRecords(missing).isEmpty());
	}

	@Test
	public void groupsByCanonicalTypedResourceAndTraceAcrossScopes() {
		OtlpTraceConverter converter = new OtlpTraceConverter(new MockOapProperties());
		Resource resource = Resource.newBuilder().addAttributes(stringAttribute("service.name", "svc"))
				.addAttributes(intAttribute("zone", 3)).build();
		ResourceSpans batch = ResourceSpans.newBuilder().setResource(resource).setSchemaUrl("resource-schema")
				.addScopeSpans(scope("instrumentation-a", "1", "scope-schema-a",
						span("0123456789abcdef0123456789abcdef", "0123456789abcdef", null, 1, 2)))
				.addScopeSpans(scope("instrumentation-b", "2", "scope-schema-b",
						span("0123456789abcdef0123456789abcdef", "fedcba9876543210", "0123456789abcdef", 2, 3)))
				.addScopeSpans(scope("instrumentation-c", "3", "scope-schema-c",
						span("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "1111111111111111", null, 3, 4))).build();

		List<MockOapTraceRecord> records = converter.toRecords(batch);
		assertEquals(2, records.size());
		MockOapTraceRecord firstTrace = records.stream()
				.filter(record -> "0123456789abcdef0123456789abcdef".equals(record.getTraceId())).findFirst().get();
		assertEquals(2, firstTrace.getSpans().size());
		assertEquals("resource-schema", firstTrace.getResourceSchemaUrl());
		assertEquals("instrumentation-a", firstTrace.getSpans().get(0).getOtlpScopeName());
		assertEquals("scope-schema-b", firstTrace.getSpans().get(1).getOtlpScopeSchemaUrl());

		Resource reordered = Resource.newBuilder().addAttributes(intAttribute("zone", 3))
				.addAttributes(stringAttribute("service.name", "svc")).build();
		ResourceSpans equivalent = ResourceSpans.newBuilder().setResource(reordered).setSchemaUrl("resource-schema")
				.addScopeSpans(scope("other-scope", "", "", span("0123456789abcdef0123456789abcdef",
						"2222222222222222", null, 1, 2))).build();
		assertEquals(firstTrace.getRecordId(), converter.toRecords(equivalent).get(0).getRecordId());

		Resource typedDifferent = Resource.newBuilder().addAttributes(stringAttribute("service.name", "svc"))
				.addAttributes(stringAttribute("zone", "3")).build();
		ResourceSpans differentIdentity = ResourceSpans.newBuilder().setResource(typedDifferent)
				.addScopeSpans(scope("", "", "", span("0123456789abcdef0123456789abcdef",
						"3333333333333333", null, 1, 2))).build();
		assertFalse(firstTrace.getRecordId().equals(converter.toRecords(differentIdentity).get(0).getRecordId()));
	}

	private static ScopeSpans scope(String name, String version, String schemaUrl, Span span) {
		return ScopeSpans.newBuilder().setScope(InstrumentationScope.newBuilder().setName(name).setVersion(version))
				.setSchemaUrl(schemaUrl).addSpans(span).build();
	}

	private static ResourceSpans resource(String serviceKey, String service, Span span) {
		return ResourceSpans.newBuilder().setResource(Resource.newBuilder().addAttributes(stringAttribute(serviceKey, service)))
				.addScopeSpans(ScopeSpans.newBuilder().addSpans(span)).build();
	}

	private static ExportTraceServiceRequest request(Resource resource, Span span) {
		return ExportTraceServiceRequest.newBuilder().addResourceSpans(ResourceSpans.newBuilder().setResource(resource)
				.addScopeSpans(ScopeSpans.newBuilder().addSpans(span))).build();
	}

	private static MockOapTraceRecord record(String traceId, String recordId, String spanId) {
		MockOapTraceRecord record = new MockOapTraceRecord();
		record.setSource("OTLP");
		record.setTraceId(traceId);
		record.setRecordId(recordId);
		MockOapSpan span = new MockOapSpan();
		span.setSpanIdString(spanId);
		span.setSpanType("INTERNAL");
		record.getSpans().add(span);
		return record;
	}

	private static Span span(String traceId, String spanId, String parentId, long start, long end) {
		Span.Builder builder = Span.newBuilder().setTraceId(ByteString.copyFrom(hex(traceId)))
				.setSpanId(ByteString.copyFrom(hex(spanId))).setStartTimeUnixNano(start).setEndTimeUnixNano(end);
		if (parentId != null) builder.setParentSpanId(ByteString.copyFrom(hex(parentId)));
		return builder.build();
	}

	private static KeyValue stringAttribute(String key, String value) {
		return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build();
	}

	private static KeyValue intAttribute(String key, long value) {
		return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setIntValue(value)).build();
	}

	private static byte[] hex(String value) {
		byte[] result = new byte[value.length() / 2];
		for (int i = 0; i < result.length; i++) {
			result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
		}
		return result;
	}
}
