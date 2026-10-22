package com.fulizhe.observability.mockoap.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fulizhe.observability.mockoap.MockOapProperties;

public class TraceSegmentBufferStatsTests {

	@Test
	public void keepsStoreAndNativeCountersUnderSeparateKeys() {
		MockOapProperties properties = new MockOapProperties();
		properties.setMaxTraces(10);
		TraceSegmentBuffer buffer = new TraceSegmentBuffer(properties);

		MockOapTraceRecord nativeRecord = new MockOapTraceRecord();
		nativeRecord.setTraceSegmentId("native-segment");
		nativeRecord.setTraceId("trace-1");
		buffer.add(nativeRecord);

		MockOapTraceRecord otlpRecord = new MockOapTraceRecord();
		otlpRecord.setSource("OTLP");
		otlpRecord.setRecordId("otlp-record");
		otlpRecord.setTraceId("trace-1");
		buffer.mergeOtlp(otlpRecord);

		Map<String, Object> stats = buffer.stats();
		assertEquals(1L, stats.get("nativeReceivedTotal"));
		assertEquals(1L, stats.get("otlpReceivedRecords"));
		assertEquals(2L, stats.get("storeReceivedTotal"));
	}
}
