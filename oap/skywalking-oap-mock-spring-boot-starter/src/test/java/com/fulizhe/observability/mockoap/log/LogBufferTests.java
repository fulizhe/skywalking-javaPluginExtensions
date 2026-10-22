package com.fulizhe.observability.mockoap.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fulizhe.observability.mockoap.MockOapProperties;

/**
 * {@link LogBuffer} 的行为钉扎:有界 FIFO、拒收自己、traceId 索引、过滤与倒序快照。
 */
public class LogBufferTests {

	private static MockOapProperties props(int maxLogs) {
		MockOapProperties p = new MockOapProperties();
		p.setMaxLogs(maxLogs);
		return p;
	}

	private static MockOapLogRecord log(String traceId, String level, String content) {
		MockOapLogRecord r = new MockOapLogRecord();
		r.setTraceId(traceId);
		r.setLevel(level);
		r.setContent(content);
		r.setService("sb-skywalking");
		r.setServiceInstance("instance-1");
		r.setTimestamp(1000L);
		return r;
	}

	@Test
	public void keepsNewestFirstAndEvictsOldestBeyondCapacity() {
		LogBuffer buffer = new LogBuffer(props(3));
		buffer.add(log("t1", "INFO", "one"));
		buffer.add(log("t1", "INFO", "two"));
		buffer.add(log("t1", "INFO", "three"));
		buffer.add(log("t1", "INFO", "four"));

		assertEquals(3, buffer.size());
		assertEquals(3, buffer.capacity());
		// receivedTotal 记的是"被接收"的条数,不随容量淘汰回退
		assertEquals(4L, buffer.receivedTotal());

		List<MockOapLogRecord> snapshot = buffer.snapshot(10);
		assertEquals(3, snapshot.size());
		assertEquals("four", snapshot.get(0).getContent());
		assertEquals("three", snapshot.get(1).getContent());
		assertEquals("two", snapshot.get(2).getContent());
	}

	@Test
	public void dropsSelfLogsBeforeCountingThem() {
		LogBuffer buffer = new LogBuffer(props(10));

		MockOapLogRecord self = log(null, "WARN", "### [mock-oap] 检测到自激(SELF-TRACING) ...");
		assertFalse(buffer.add(self), "采集器自己的日志必须被拒收");

		assertEquals(0, buffer.size());
		assertEquals(0L, buffer.receivedTotal());
		assertEquals(1L, buffer.droppedSelfTotal());

		// 业务日志照常入库
		assertTrue(buffer.add(log("t1", "INFO", "business log")));
		assertEquals(1, buffer.size());
		assertEquals(1L, buffer.receivedTotal());
	}

	@Test
	public void rejectsNullRecord() {
		LogBuffer buffer = new LogBuffer(props(10));
		assertFalse(buffer.add(null));
		assertEquals(0, buffer.size());
		assertEquals(0L, buffer.receivedTotal());
	}

	@Test
	public void indexFollowsEviction() {
		LogBuffer buffer = new LogBuffer(props(2));
		buffer.add(log("old", "INFO", "a"));
		buffer.add(log("keep", "INFO", "b"));
		buffer.add(log("keep", "INFO", "c"));

		assertEquals(0, buffer.countByTraceId("old"));
		assertEquals(2, buffer.countByTraceId("keep"));
		assertEquals(2, buffer.findByTraceId("keep").size());
		assertEquals("c", buffer.findByTraceId("keep").get(0).getContent());
		assertTrue(buffer.findByTraceId("missing").isEmpty());
	}

	@Test
	public void findsLogsBySegmentAndSpanOnly() {
		LogBuffer buffer = new LogBuffer(props(10));

		MockOapLogRecord entry = log("t1", "INFO", "entry");
		entry.setTraceSegmentId("seg-1");
		entry.setSpanId(0);
		MockOapLogRecord exit = log("t1", "ERROR", "exit");
		exit.setTraceSegmentId("seg-1");
		exit.setSpanId(1);
		MockOapLogRecord otherSegment = log("t1", "INFO", "other segment");
		otherSegment.setTraceSegmentId("seg-2");
		otherSegment.setSpanId(0);
		MockOapLogRecord noSegment = log("t1", "INFO", "no segment");

		buffer.add(entry);
		buffer.add(exit);
		buffer.add(otherSegment);
		buffer.add(noSegment);

		List<MockOapLogRecord> entryLogs = buffer.findBySpan("t1", "seg-1", 0);
		assertEquals(1, entryLogs.size());
		assertEquals("entry", entryLogs.get(0).getContent());
		assertEquals("exit", buffer.findBySpan("t1", "seg-1", 1).get(0).getContent());

		assertTrue(buffer.findBySpan("t1", "seg-1", 9).isEmpty());
		assertTrue(buffer.findBySpan("t2", "seg-1", 0).isEmpty());
		// 没有段 id 的日志定位不到 span,只能在 trace 级日志里看
		assertTrue(buffer.findBySpan("t1", null, 0).isEmpty());
		assertEquals(4, buffer.findByTraceId("t1").size());
	}

	@Test
	public void spanLogsStayNewestFirst() {
		LogBuffer buffer = new LogBuffer(props(10));
		MockOapLogRecord first = log("t1", "INFO", "first");
		first.setTraceSegmentId("seg-1");
		first.setSpanId(0);
		MockOapLogRecord second = log("t1", "INFO", "second");
		second.setTraceSegmentId("seg-1");
		second.setSpanId(0);
		buffer.add(first);
		buffer.add(second);

		List<MockOapLogRecord> logs = buffer.findBySpan("t1", "seg-1", 0);
		assertEquals(2, logs.size());
		assertEquals("second", logs.get(0).getContent());
		assertEquals("first", logs.get(1).getContent());
	}

	@Test
	public void filtersByTraceIdLevelAndKeyword() {
		LogBuffer buffer = new LogBuffer(props(10));
		buffer.add(log("t1", "INFO", "GET /orders 200"));
		buffer.add(log("t1", "ERROR", "GET /orders 500 boom"));
		buffer.add(log("t2", "INFO", "POST /pay ok"));

		assertEquals(2, buffer.query("t1", null, null, 10).size());
		assertEquals(1, buffer.query(null, "error", null, 10).size()); // level 大小写不敏感
		assertEquals(1, buffer.query(null, null, "boom", 10).size());
		assertEquals(1, buffer.query("t1", "ERROR", "boom", 10).size());
		assertTrue(buffer.query("t1", "ERROR", "nope", 10).isEmpty());
	}

	@Test
	public void queryLimitCapsResultsWithoutDroppingTheStore() {
		LogBuffer buffer = new LogBuffer(props(10));
		for (int i = 0; i < 5; i++) {
			buffer.add(log("t", "INFO", "m" + i));
		}
		List<MockOapLogRecord> top2 = buffer.query(null, null, null, 2);
		assertEquals(2, top2.size());
		assertEquals("m4", top2.get(0).getContent());
		assertEquals(5, buffer.size());
	}

	@Test
	public void blankFiltersAreIgnored() {
		LogBuffer buffer = new LogBuffer(props(10));
		buffer.add(log("t1", "INFO", "hello"));
		assertEquals(1, buffer.query("  ", " ", "", 10).size());
		assertEquals(1, buffer.query(null, null, null, 0).size()); // limit<=0 -> 不限(受容量约束)
	}

	@Test
	public void statsExposeCounters() {
		LogBuffer buffer = new LogBuffer(props(5));
		buffer.add(log("t", "INFO", "x"));
		buffer.add(log(null, "INFO", "[mock-oap] self"));

		assertEquals(1, buffer.stats().get("size"));
		assertEquals(5, buffer.stats().get("capacity"));
		assertEquals(1L, buffer.stats().get("receivedTotal"));
		assertEquals(1L, buffer.stats().get("droppedSelfTotal"));
	}

	@Test
	public void levelDetectionPrefersTagThenParsesText() {
		assertEquals("WARN", MockOapLogs.detectLevel("anything", java.util.Collections.singletonMap("level", "warn")));
		assertEquals("ERROR", MockOapLogs.detectLevel("2026-10-19 10:00:00.000 [tid] [main] ERROR c.f.Demo - boom", null));
		assertEquals("INFO", MockOapLogs.detectLevel("2026-10-19 10:00:00.000 INFO  c.f.Demo - ok", null));
		// "traceId" 里不含独立单词级别,不应误判
		assertNull(MockOapLogs.detectLevel("traceId=abc information", null));
		assertNull(MockOapLogs.detectLevel(null, null));
	}
}
