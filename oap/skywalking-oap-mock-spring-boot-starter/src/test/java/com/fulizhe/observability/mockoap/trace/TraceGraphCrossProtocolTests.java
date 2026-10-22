package com.fulizhe.observability.mockoap.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Verifies that native sw8 segments and the C++ OTLP bridge form one trace tree. */
public class TraceGraphCrossProtocolTests {

	@Test
	public void cppOtlpSpanJoinsNativeGoAndNodeSegments() {
		MockOapTraceRecord java = nativeRecord("trace-1", "java-seg", "sb-skywalking",
				nativeSpan(0, -1, "Entry", "GET /mock-oap/demo/go-chain"));
		MockOapTraceRecord go = nativeRecord("trace-1", "go-seg", "go-service",
				nativeSpan(0, -1, "Entry", "GET /api/forward"),
				nativeSpan(1, 0, "Exit", "GET cpp-service:8083/api/forward"));
		MockOapTraceRecord nodeRecord = nativeRecord("trace-1", "node-seg", "node-service",
				nativeSpan(0, -1, "Entry", "GET /api/forward"));

		MockOapSpan nodeSpan = nodeRecord.getSpans().get(0);
		MockOapSpanRef ref = new MockOapSpanRef();
		ref.setRefType("CrossProcess");
		ref.setParentTraceSegmentId("cpp-seg");
		ref.setParentSpanId(123);
		nodeSpan.getRefs().add(ref);

		MockOapTraceRecord cpp = new MockOapTraceRecord();
		cpp.setSource("OTLP");
		cpp.setRecordId("OTLP:resource:trace-1");
		cpp.setTraceId("trace-1");
		cpp.setService("cpp-service");
		MockOapSpan server = otlpSpan("aaaaaaaaaaaaaaaa", "SERVER", "GET /api/forward",
				"cpp-seg", "123", "go-seg", "1");
		MockOapSpan client = otlpSpan("bbbbbbbbbbbbbbbb", "CLIENT", "GET node-service:8081/api/forward",
				"cpp-seg", "124", null, null);
		client.setParentSpanIdString("aaaaaaaaaaaaaaaa");
		cpp.getSpans().add(server);
		cpp.getSpans().add(client);

		List<MockOapTraceRecord> records = new ArrayList<MockOapTraceRecord>(
				Arrays.asList(java, go, cpp, nodeRecord));
		Map<String, Object> graph = TraceGraphAssembler.assemble("trace-1", records);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
		// Java entry + Go entry/exit + C++ server/client + Node entry
		assertEquals(6, nodes.size());
		Map<String, Object> goExit = find(nodes, "go-service", "GET cpp-service:8083/api/forward");
		Map<String, Object> cppServer = find(nodes, "cpp-service", "GET /api/forward");
		Map<String, Object> cppClient = find(nodes, "cpp-service",
				"GET node-service:8081/api/forward");
		Map<String, Object> nodeEntry = find(nodes, "node-service", "GET /api/forward");
		assertNotNull(goExit);
		assertNotNull(cppServer);
		assertNotNull(cppClient);
		assertNotNull(nodeEntry);

		assertEquals(goExit.get("key"), cppServer.get("parentKey"));
		assertEquals(cppServer.get("key"), cppClient.get("parentKey"));
		assertEquals(cppServer.get("key"), nodeEntry.get("parentKey"));
		assertTrue(((Number) cppServer.get("depth")).intValue() > ((Number) goExit.get("depth")).intValue());
		assertTrue(((Number) nodeEntry.get("depth")).intValue() > ((Number) cppServer.get("depth")).intValue());
	}

	private static Map<String, Object> find(List<Map<String, Object>> nodes, String service,
			String operation) {
		for (Map<String, Object> node : nodes) {
			if (service.equals(node.get("service")) && operation.equals(node.get("operationName"))) {
				return node;
			}
		}
		return null;
	}

	private static MockOapTraceRecord nativeRecord(String traceId, String segmentId, String service,
			MockOapSpan... spans) {
		MockOapTraceRecord record = new MockOapTraceRecord();
		record.setTraceId(traceId);
		record.setTraceSegmentId(segmentId);
		record.setService(service);
		for (MockOapSpan span : spans) {
			record.getSpans().add(span);
		}
		return record;
	}

	private static MockOapSpan nativeSpan(int spanId, int parentSpanId, String type, String operation) {
		MockOapSpan span = new MockOapSpan();
		span.setSpanId(spanId);
		span.setParentSpanId(parentSpanId);
		span.setSpanType(type);
		span.setOperationName(operation);
		return span;
	}

	private static MockOapSpan otlpSpan(String spanId, String type, String operation,
			String segmentId, String sw8SpanId, String parentSegmentId, String parentSpanId) {
		MockOapSpan span = new MockOapSpan();
		span.setSpanIdString(spanId);
		span.setSpanType(type);
		span.setOperationName(operation);
		Map<String, Object> attributes = new LinkedHashMap<String, Object>();
		attributes.put("sw8.segment.id", segmentId);
		attributes.put("sw8.span.id", sw8SpanId);
		attributes.put("sw8.parent.segment.id", parentSegmentId);
		attributes.put("sw8.parent.span.id", parentSpanId);
		span.setOtlpAttributes(attributes);
		return span;
	}
}
