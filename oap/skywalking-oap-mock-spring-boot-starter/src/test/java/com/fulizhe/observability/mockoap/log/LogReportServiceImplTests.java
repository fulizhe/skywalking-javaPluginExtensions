package com.fulizhe.observability.mockoap.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.logging.v3.JSONLog;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.apm.network.logging.v3.LogDataBody;
import org.apache.skywalking.apm.network.logging.v3.LogTags;
import org.apache.skywalking.apm.network.logging.v3.TextLog;
import org.apache.skywalking.apm.network.logging.v3.TraceContext;
import org.junit.jupiter.api.Test;

import com.fulizhe.observability.mockoap.MockOapProperties;

/**
 * {@link LogReportServiceImpl} 的协议侧行为:LogData -> {@link MockOapLogRecord} 的字段抽取,
 * 以及"拒收自己"经过 {@link LogBuffer} 后不会污染计数。
 *
 * <p>只构造 protobuf 报文,不真正起 gRPC —— 免去 agent/端口依赖。
 */
public class LogReportServiceImplTests {

	private static LogReportServiceImpl service(int maxLogs) {
		MockOapProperties p = new MockOapProperties();
		p.setMaxLogs(maxLogs);
		return new LogReportServiceImpl(new LogBuffer(p));
	}

	private static LogData.Builder base() {
		return LogData.newBuilder()
				.setTimestamp(1_763_000_000_000L)
				.setService("sb-skywalking")
				.setServiceInstance("instance-1")
				.setEndpoint("/orders")
				.setLayer("Http")
				.setTraceContext(TraceContext.newBuilder()
						.setTraceId("trace-1")
						.setTraceSegmentId("seg-1")
						.setSpanId(2)
						.build());
	}

	@Test
	public void extractsTextBodyTraceContextAndTags() {
		LogData data = base()
				.setBody(LogDataBody.newBuilder()
						.setType("text")
						.setText(TextLog.newBuilder().setText("2026-10-19 10:00:00.000 [tid] [http-nio] INFO  c.f.Demo - hello").build())
						.build())
				.setTags(LogTags.newBuilder()
						.addData(KeyStringValuePair.newBuilder().setKey("level").setValue("WARN").build())
						.build())
				.build();

		MockOapLogRecord r = service(10).toRecord(data);

		assertEquals(1_763_000_000_000L, r.getTimestamp());
		assertEquals("sb-skywalking", r.getService());
		assertEquals("instance-1", r.getServiceInstance());
		assertEquals("/orders", r.getEndpoint());
		assertEquals("Http", r.getLayer());
		assertEquals("TEXT", r.getContentType());
		assertEquals("trace-1", r.getTraceId());
		assertEquals("seg-1", r.getTraceSegmentId());
		assertEquals(2, r.getSpanId());
		assertTrue(r.getContent().contains("hello"));
		// tags 里的 level 优先于正文解析
		assertEquals("WARN", r.getLevel());
		assertEquals("WARN", r.getTags().get("level"));
	}

	@Test
	public void parsesLevelFromTextWhenNoLevelTag() {
		LogData data = base()
				.setBody(LogDataBody.newBuilder()
						.setType("text")
						.setText(TextLog.newBuilder().setText("2026-10-19 10:00:00.000 [tid] [main] ERROR c.f.Demo - boom").build())
						.build())
				.build();

		assertEquals("ERROR", service(10).toRecord(data).getLevel());
	}

	@Test
	public void extractsJsonBody() {
		LogData data = base()
				.setBody(LogDataBody.newBuilder()
						.setType("json")
						.setJson(JSONLog.newBuilder().setJson("{\"msg\":\"hi\"}").build())
						.build())
				.build();

		MockOapLogRecord r = service(10).toRecord(data);
		assertEquals("JSON", r.getContentType());
		assertEquals("{\"msg\":\"hi\"}", r.getContent());
		assertEquals("trace-1", r.getTraceId());
	}

	@Test
	public void normalizesAgentNoTraceSentinel() {
		LogData data = base()
				.setTraceContext(TraceContext.newBuilder()
						.setTraceId("N/A")
						.setTraceSegmentId("N/A")
						.setSpanId(-1)
						.build())
				.setBody(LogDataBody.newBuilder()
						.setType("text")
						.setText(TextLog.newBuilder().setText("send metrics error EOF").build())
						.build())
				.build();

		MockOapLogRecord r = service(10).toRecord(data);
		assertNull(r.getTraceId());
		assertNull(r.getTraceSegmentId());
		assertEquals(-1, r.getSpanId());
	}

	@Test
	public void missingOptionalSectionsDegradeGracefully() {
		LogData data = LogData.newBuilder().setTimestamp(5L).setService("svc").build();
		MockOapLogRecord r = service(10).toRecord(data);

		assertEquals(5L, r.getTimestamp());
		assertEquals("", r.getContent());
		assertEquals("NONE", r.getContentType());
		assertNull(r.getTraceId());
		assertEquals(0, r.getSpanId());
		assertTrue(r.getTags().isEmpty());
		assertTrue(r.getReceivedAt() > 0L);
	}

	@Test
	public void selfLogsNeverReachTheBuffer() {
		MockOapProperties p = new MockOapProperties();
		p.setMaxLogs(10);
		LogBuffer buffer = new LogBuffer(p);
		LogReportServiceImpl svc = new LogReportServiceImpl(buffer);

		LogData self = base()
				.setBody(LogDataBody.newBuilder()
						.setType("text")
						.setText(TextLog.newBuilder().setText("### [mock-oap] segment received: service=sb-skywalking").build())
						.build())
				.build();

		buffer.add(svc.toRecord(self));
		assertEquals(0, buffer.size());
		assertEquals(0L, buffer.receivedTotal());
		assertEquals(1L, buffer.droppedSelfTotal());
	}
}
