package org.apache.skywalking.apm.agent.core.reporter.logfile.storage;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.skywalking.apm.agent.core.reporter.logfile.Log;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * H2TraceSegmentStorage 单元测试。
 * <p>
 * 只经接口断言（storeLog → snapshot/size），不碰 SQL / 内部字段。
 * 覆盖：traceId 合并、logs/span 计数、start_time 排序、环形载荷 JSON 往返、size 与水位上限。
 * </p>
 */
public class H2TraceSegmentStorageTest {

    private H2TraceSegmentStorage storage;
    private File cappedDir;
    private File cappedFile;

    @Before
    public void setUp() {
        cappedDir = new File(System.getProperty("java.io.tmpdir"), "h2store-test-" + System.nanoTime());
        Assert.assertTrue(cappedDir.mkdirs());
        cappedFile = new File(cappedDir, "payload.capped.db");
        storage = new H2TraceSegmentStorage(true, 2000, true, cappedFile, 1024L * 1024L);
        storage.clear();
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
        if (cappedDir != null) {
            final File[] files = cappedDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            cappedDir.delete();
        }
    }

    // ========== traceId 合并 ==========

    @Test
    public void singleSegment_singleTraceId_oneLog() {
        final Log log = createLog("t1", "s1", 1000L, false);
        storage.storeLog(log);

        final Map<String, Map<String, Object>> snapshot = storage.snapshot();
        Assert.assertEquals("size should be 1", 1, storage.size());
        Assert.assertTrue("snapshot should contain t1", snapshot.containsKey("t1"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> logs = (List<Map<String, Object>>) snapshot.get("t1").get("logs");
        Assert.assertEquals("should have 1 log", 1, logs.size());
    }

    @Test
    public void twoSegments_sameTraceId_mergedIntoOneTrace() {
        storage.storeLog(createLog("t1", "s1", 1000L, false));
        storage.storeLog(createLog("t1", "s2", 2000L, false));

        Assert.assertEquals("size should be 1 (same traceId)", 1, storage.size());
        final Map<String, Map<String, Object>> snapshot = storage.snapshot();
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> logs = (List<Map<String, Object>>) snapshot.get("t1").get("logs");
        Assert.assertEquals("should have 2 logs merged", 2, logs.size());
    }

    @Test
    public void twoSegments_differentTraceIds_bothPresent() {
        storage.storeLog(createLog("t1", "s1", 1000L, false));
        storage.storeLog(createLog("t2", "s2", 2000L, false));

        Assert.assertEquals("size should be 2", 2, storage.size());
        final Map<String, Map<String, Object>> snapshot = storage.snapshot();
        Assert.assertTrue("snapshot should contain t1", snapshot.containsKey("t1"));
        Assert.assertTrue("snapshot should contain t2", snapshot.containsKey("t2"));
    }

    // ========== start_time 排序 ==========

    @Test
    public void segmentsSortedByStartTimeAscending() {
        // Insert in reverse order
        storage.storeLog(createLog("t1", "s3", 3000L, false));
        storage.storeLog(createLog("t1", "s1", 1000L, false));
        storage.storeLog(createLog("t1", "s2", 2000L, false));

        final Map<String, Map<String, Object>> snapshot = storage.snapshot();
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> logs = (List<Map<String, Object>>) snapshot.get("t1").get("logs");
        Assert.assertEquals("should have 3 logs", 3, logs.size());

        // Verify order: s1 (1000) → s2 (2000) → s3 (3000)
        Assert.assertEquals("first log should be s1", "s1", logs.get(0).get("traceSegmentId"));
        Assert.assertEquals("second log should be s2", "s2", logs.get(1).get("traceSegmentId"));
        Assert.assertEquals("third log should be s3", "s3", logs.get(2).get("traceSegmentId"));
    }

    // ========== data_binary JSON 往返 ==========

    @Test
    public void dataBinaryJsonRoundTrip_preservesFields() {
        final Log log = createLog("t1", "seg-001", 5000L, true);
        log.setService("order-service");
        log.setServiceInstance("instance-2");
        log.setIsSizeLimited(true);

        storage.storeLog(log);

        final Map<String, Map<String, Object>> snapshot = storage.snapshot();
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> logs = (List<Map<String, Object>>) snapshot.get("t1").get("logs");
        final Map<String, Object> logMap = logs.get(0);

        Assert.assertEquals("traceId", "t1", logMap.get("traceId"));
        Assert.assertEquals("traceSegmentId", "seg-001", logMap.get("traceSegmentId"));
        Assert.assertEquals("service", "order-service", logMap.get("service"));
        Assert.assertEquals("serviceInstance", "instance-2", logMap.get("serviceInstance"));
        Assert.assertEquals("isSizeLimited", Boolean.TRUE, logMap.get("isSizeLimited"));

        // Verify span fields round-trip
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> spans = (List<Map<String, Object>>) logMap.get("spans");
        Assert.assertEquals("should have 1 span", 1, spans.size());
        final Map<String, Object> spanMap = spans.get(0);
        Assert.assertEquals("spanId", Integer.valueOf(0), toInteger(spanMap.get("spanId")));
        Assert.assertEquals("parentSpanId", Integer.valueOf(-1), toInteger(spanMap.get("parentSpanId")));
        Assert.assertEquals("operationName", "GET /test", spanMap.get("operationName"));
        Assert.assertEquals("spanType", "Entry", spanMap.get("spanType"));
        Assert.assertEquals("isError", Boolean.TRUE, spanMap.get("isError"));
    }

    // ========== size 与水位上限 ==========

    @Test
    public void sizeReturnsDistinctTraceCount() {
        storage.storeLog(createLog("t1", "s1", 1000L, false));
        storage.storeLog(createLog("t1", "s2", 2000L, false));
        storage.storeLog(createLog("t2", "s3", 3000L, false));
        storage.storeLog(createLog("t3", "s4", 4000L, false));

        Assert.assertEquals("distinct trace count should be 3", 3, storage.size());
    }

    @Test
    public void rowCapEnforced_afterExceedingMaxRows() {
        // Create storage with small cap
        storage.close();
        storage = new H2TraceSegmentStorage(true, 3, true, new File(cappedDir, "payload2.capped.db"), 1024L * 1024L);
        storage.clear();

        // 水位校验按行数节流（enforceRowCap 中每 1024 行一次），故需写满一个间隔才触发；
        // 触发后仅保留最后 shadowMaxRows(=3) 行（t1022 ~ t1024）。
        final int capCheckInterval = 1024;
        for (int i = 1; i <= capCheckInterval; i++) {
            storage.storeLog(createLog("t" + i, "s" + i, 1000L * i, false));
        }

        Assert.assertEquals("size should be capped to 3", 3, storage.size());
        final Map<String, Map<String, Object>> snapshot = storage.snapshot();
        Assert.assertFalse("t1 should be evicted", snapshot.containsKey("t1"));
        Assert.assertFalse("t" + (capCheckInterval - 3) + " should be evicted",
                snapshot.containsKey("t" + (capCheckInterval - 3)));
        Assert.assertTrue("t" + (capCheckInterval - 2) + " should remain",
                snapshot.containsKey("t" + (capCheckInterval - 2)));
        Assert.assertTrue("t" + (capCheckInterval - 1) + " should remain",
                snapshot.containsKey("t" + (capCheckInterval - 1)));
        Assert.assertTrue("t" + capCheckInterval + " should remain",
                snapshot.containsKey("t" + capCheckInterval));
    }

    // ========== disabled storage ==========

    @Test
    public void disabledStorage_noOpsNoErrors() {
        storage.close();
        storage = new H2TraceSegmentStorage(false, 2000, false, null, 0L);

        storage.storeLog(createLog("t1", "s1", 1000L, false));
        Assert.assertEquals("disabled storage should have size 0", 0, storage.size());
        Assert.assertTrue("disabled storage snapshot should be empty", storage.snapshot().isEmpty());
        Assert.assertEquals("disabled storage should have 0 errors", 0, storage.getErrorCount());
    }

    // ========== span 计数 ==========

    @Test
    public void multipleSpansInOneSegment_allPreserved() {
        final Log log = new Log();
        log.setTraceId("t1");
        log.setTraceSegmentId("s1");
        log.setService("svc");
        log.setServiceInstance("inst");
        log.setIsSizeLimited(false);

        final List<Log.SpanInfo> spans = new ArrayList<Log.SpanInfo>();
        spans.add(createSpan(0, -1, "GET /api", "Entry", 1000L, 1100L, false));
        spans.add(createSpan(1, 0, "GET /db", "Exit", 1050L, 1080L, false));
        spans.add(createSpan(2, 0, "GET /cache", "Exit", 1100L, 1120L, true));
        log.setSpans(spans);

        storage.storeLog(log);

        final Map<String, Map<String, Object>> snapshot = storage.snapshot();
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> logs = (List<Map<String, Object>>) snapshot.get("t1").get("logs");
        Assert.assertEquals("should have 1 log", 1, logs.size());

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> spanMaps = (List<Map<String, Object>>) logs.get(0).get("spans");
        Assert.assertEquals("should have 3 spans", 3, spanMaps.size());
        Assert.assertEquals("span 0 operationName", "GET /api", spanMaps.get(0).get("operationName"));
        Assert.assertEquals("span 1 operationName", "GET /db", spanMaps.get(1).get("operationName"));
        Assert.assertEquals("span 2 operationName", "GET /cache", spanMaps.get(2).get("operationName"));
    }

    // ========== 查询门面 queryTrace / recentTraces ==========

    @Test
    public void queryTrace_hit_returnsLogs() {
        storage.storeLog(createLog("t1", "s1", 1000L, false));

        final Map<String, Object> view = storage.queryTrace("t1");
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> logs = (List<Map<String, Object>>) view.get("logs");
        Assert.assertEquals("hit should return 1 log", 1, logs.size());
        Assert.assertEquals("payload not expired", Boolean.FALSE, view.get("payloadExpired"));
    }

    @Test
    public void queryTrace_miss_returnsEmpty() {
        final Map<String, Object> view = storage.queryTrace("no-such-trace");
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> logs = (List<Map<String, Object>>) view.get("logs");
        Assert.assertTrue("miss should return empty logs", logs.isEmpty());
        Assert.assertEquals("not expired on miss", Boolean.FALSE, view.get("payloadExpired"));
    }

    @Test
    public void queryTrace_expiredPayload_marksExpired() {
        // 小环形文件：持续写入必然覆盖最旧 payload
        storage.close();
        storage = new H2TraceSegmentStorage(true, 2000, true, new File(cappedDir, "small.capped.db"), 4096L);
        for (int i = 0; i < 500; i++) {
            storage.storeLog(createLog("t1", "s" + i, 1000L + i, false));
        }

        final Map<String, Object> view = storage.queryTrace("t1");
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> logs = (List<Map<String, Object>>) view.get("logs");
        Assert.assertTrue("some payloads should be overwritten", logs.size() < 500);
        Assert.assertEquals("payloadExpired should be true", Boolean.TRUE, view.get("payloadExpired"));
    }

    @Test
    public void recentTraces_returnsHeadersWithPayloadState() {
        storage.storeLog(createLog("t1", "s1", 1000L, false));
        storage.storeLog(createLog("t2", "s2", 2000L, true));

        final List<Map<String, Object>> recent = storage.recentTraces(10);
        Assert.assertEquals("should return 2 headers", 2, recent.size());
        Assert.assertNotNull("traceId present", recent.get(0).get("traceId"));
        Assert.assertNotNull("hasPayload present", recent.get(0).get("hasPayload"));
        Assert.assertNotNull("payloadExpired present", recent.get(0).get("payloadExpired"));
    }

    // ========== 全量持久层（normal 也入 H2；trace_level 列已删） ==========

    @Test
    public void normalAndErrorSegmentsBothPersisted() {
        storage.storeLog(createLog("t-normal", "s1", 1000L, false));
        storage.storeLog(createLog("t-error", "s2", 2000L, true));

        final List<Map<String, Object>> recent = storage.recentTraces(10);
        Assert.assertEquals("normal 也入 H2（全量，无收窄）", 2, recent.size());

        boolean normalSeen = false;
        boolean errorSeen = false;
        for (Map<String, Object> r : recent) {
            if ("t-normal".equals(r.get("traceId"))) {
                normalSeen = true;
                Assert.assertEquals("normal is_error=false", Boolean.FALSE, r.get("isError"));
            }
            if ("t-error".equals(r.get("traceId"))) {
                errorSeen = true;
                Assert.assertEquals("error is_error=true", Boolean.TRUE, r.get("isError"));
            }
        }
        Assert.assertTrue("normal trace persisted", normalSeen);
        Assert.assertTrue("error trace persisted", errorSeen);
    }

    // ========== 慢查询（endpoint + 阈值，行列表） ==========

    @Test
    public void querySlowTraces_filtersByEndpointAndLatencyDescending() {
        storage.storeLog(createLog("t1", "s1", "GET:/slow", 1000L, 900L, false));
        storage.storeLog(createLog("t2", "s2", "GET:/slow", 2000L, 5000L, true));
        storage.storeLog(createLog("t3", "s3", "GET:/fast", 3000L, 9000L, false));

        final List<Map<String, Object>> above1000 = storage.querySlowTraces("GET:/slow", 1000L, 50);
        Assert.assertEquals("仅 GET:/slow 且 latency>=1000", 1, above1000.size());
        Assert.assertEquals("t2", above1000.get(0).get("traceId"));
        Assert.assertEquals(5000, ((Number) above1000.get(0).get("latency")).intValue());
        Assert.assertEquals(Boolean.TRUE, above1000.get(0).get("isError"));
        Assert.assertNotNull("payload 状态存在", above1000.get(0).get("payloadExpired"));

        final List<Map<String, Object>> above100 = storage.querySlowTraces("GET:/slow", 100L, 50);
        Assert.assertEquals("阈值 100 两条都命中，按 latency 降序", 2, above100.size());
        Assert.assertEquals("t2", above100.get(0).get("traceId"));
        Assert.assertEquals("t1", above100.get(1).get("traceId"));
    }

    @Test
    public void querySlowTraces_blankEndpointOrZeroLimit_returnsEmpty() {
        storage.storeLog(createLog("t1", "s1", "GET:/slow", 1000L, 900L, false));
        Assert.assertTrue(storage.querySlowTraces("", 0L, 50).isEmpty());
        Assert.assertTrue(storage.querySlowTraces(null, 0L, 50).isEmpty());
        Assert.assertTrue(storage.querySlowTraces("GET:/slow", 0L, 0).isEmpty());
    }

    // ========== helpers ==========

    private static Log createLog(final String traceId, final String segmentId,
            final String endpoint, final long startTime, final long latency, final boolean isError) {
        final Log log = new Log();
        log.setTraceId(traceId);
        log.setTraceSegmentId(segmentId);
        log.setService("test-service");
        log.setServiceInstance("test-instance");
        log.setIsSizeLimited(false);
        log.setSpans(Collections.singletonList(
                createSpan(0, -1, endpoint, "Entry", startTime, startTime + latency, isError)));
        return log;
    }

    private static Log createLog(final String traceId, final String segmentId,
            final long startTime, final boolean isError) {
        final Log log = new Log();
        log.setTraceId(traceId);
        log.setTraceSegmentId(segmentId);
        log.setService("test-service");
        log.setServiceInstance("test-instance");
        log.setIsSizeLimited(false);
        log.setSpans(Collections.singletonList(createSpan(0, -1, "GET /test", "Entry", startTime, startTime + 100, isError)));
        return log;
    }

    private static Log.SpanInfo createSpan(final int spanId, final int parentSpanId,
            final String operationName, final String spanType,
            final long startTime, final long endTime, final boolean isError) {
        final Log.SpanInfo span = new Log.SpanInfo();
        span.setSpanId(spanId);
        span.setParentSpanId(parentSpanId);
        span.setOperationName(operationName);
        span.setStartTime(startTime);
        span.setEndTime(endTime);
        span.setSpanType(spanType);
        span.setSpanLayer("HTTP");
        span.setComponentId(1);
        span.setIsError(isError);
        span.setTagList(new ArrayList<Log.Tag>());
        span.setLogList(new ArrayList<String>());
        return span;
    }

    private static Integer toInteger(final Object value) {
        if (value instanceof Number) {
            return Integer.valueOf(((Number) value).intValue());
        }
        return null;
    }
}
