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

    // ========== 自身状态快照（/inner/sw/self-stat 的数据源）============

    @Test
    @SuppressWarnings("unchecked")
    public void selfStatSnapshot_liveRowCountMatchesSizeAndCarriesCappedGeometry() {
        storage.storeLog(createLog("t1", "s1", 1000L, false));
        storage.storeLog(createLog("t2", "s2", 2000L, false));
        storage.storeLog(createLog("t2", "s3", 3000L, false));

        final Map<String, Object> snapshot = storage.selfStatSnapshot();
        final Map<String, Object> h2 = (Map<String, Object>) snapshot.get("h2");
        final Map<String, Object> backlog = (Map<String, Object>) snapshot.get("backlog");
        final Map<String, Object> capped = (Map<String, Object>) snapshot.get("capped");

        Assert.assertEquals(Boolean.TRUE, h2.get("enabled"));
        Assert.assertEquals("存活行数（段数）应与插入数一致", Long.valueOf(3L), h2.get("rows"));
        Assert.assertEquals("行数水位上限回显配置", Integer.valueOf(2000), h2.get("maxRows"));
        Assert.assertEquals("估算字节 = 行数 × 单行成本", Long.valueOf(3L * 800L), h2.get("estimatedBytes"));
        Assert.assertEquals("队列容量有界", Integer.valueOf(storage.getWriteQueueCapacity()), backlog.get("capacity"));

        Assert.assertEquals(Boolean.TRUE, capped.get("enabled"));
        Assert.assertTrue("写指针应已前进", ((Long) capped.get("currIndex")).longValue() > 0L);
        Assert.assertTrue("数据区 = 1MB - 16B",
                ((Long) capped.get("dataLenBytes")).longValue() == 1024L * 1024L - 16L);
        final Map<String, Object> cappedStats = (Map<String, Object>) capped.get("stats");
        Assert.assertEquals("每段一个载荷块", Long.valueOf(3L), cappedStats.get("writeCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void selfStatSnapshot_afterRowCap_liveRowCountConvergesToCap() {
        storage.close();
        storage = new H2TraceSegmentStorage(true, 3, true, new File(cappedDir, "payload3.capped.db"), 1024L * 1024L);
        storage.clear();
        for (int i = 1; i <= 1024; i++) {
            storage.storeLog(createLog("t" + i, "s" + i, 1000L * i, false));
        }

        final Map<String, Object> h2 = (Map<String, Object>) storage.selfStatSnapshot().get("h2");
        Assert.assertEquals("增量口径的存活行数应与实际行数一致（不能只增不减）",
                Long.valueOf(storage.size()), h2.get("rows"));
        Assert.assertEquals("清理掉的行数应被记下", Long.valueOf(1021L), h2.get("evictedRows"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void selfStatSnapshot_timeRangeTracksOldestAndNewestSegment() {
        storage.storeLog(createLog("t1", "s1", 1000L, false));
        // 插到 1024 行触发一次水位刷新后，最老行才是被 seek 确认过的那行
        for (int i = 2; i <= 1024; i++) {
            storage.storeLog(createLog("t" + i, "s" + i, 1000L * i, false));
        }

        final Map<String, Object> h2 = (Map<String, Object>) storage.selfStatSnapshot().get("h2");
        // start_time 递增：最老存活行是第 1 段(1000)、最新是第 1024 段(1024000)
        Assert.assertEquals(Long.valueOf(1000L), h2.get("oldestStartTimeMs"));
        Assert.assertEquals(Long.valueOf(1000L * 1024L), h2.get("newestStartTimeMs"));
        Assert.assertEquals(Long.valueOf(1000L * 1024L - 1000L), h2.get("coveredSpanMs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void selfStatSnapshot_oldestLiveRowRefreshesAgainOnSecondRowCap() {
        // 必须写**两轮** 1024 行：水位刷新每 1024 行一次，而"合并旧值"那类错误只在
        // 第二次刷新才显形 —— 第一次刷新时旧值还是 MAX_VALUE，取 min 与正确做法同结果。
        storage.close();
        storage = new H2TraceSegmentStorage(true, 3, true, new File(cappedDir, "payload5.capped.db"), 1024L * 1024L);
        storage.clear();
        for (int i = 1; i <= 2048; i++) {
            storage.storeLog(createLog("t" + i, "s" + i, 1000L * i, false));
        }

        final Map<String, Object> h2 = (Map<String, Object>) storage.selfStatSnapshot().get("h2");
        Assert.assertEquals("存活 3 段", Long.valueOf(3L), h2.get("rows"));
        Assert.assertEquals("第二次刷新后最老存活段应是第 2046 段（第一轮最老的第 1022 段早已被清掉）",
                Long.valueOf(1000L * 2046L), h2.get("oldestStartTimeMs"));
        Assert.assertEquals(Long.valueOf(1000L * 2048L), h2.get("newestStartTimeMs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void selfStatSnapshot_noPayloadStorage_reportsOldestPayloadIdAsMinusOneNotZero() {
        // capped 关闭 → writePayload 返回 -1 → payload_id 落库为 NULL。
        // 这正是 wasNull() 的用武之地：getLong() 会把 NULL 读成 0，而 0 是合法的首个
        // 逻辑偏移，不判 wasNull 就会把"没有载荷"误报成"指向环里最老的那块"。
        storage.close();
        storage = new H2TraceSegmentStorage(true, 2000, false, null, 0L);
        storage.clear();
        for (int i = 1; i <= 1024; i++) {
            storage.storeLog(createLog("t" + i, "s" + i, 1000L * i, false));
        }

        final Map<String, Object> capped = (Map<String, Object>) storage.selfStatSnapshot().get("capped");
        Assert.assertEquals("无载荷时必须是 -1，不能是 0", Long.valueOf(-1L), capped.get("oldestPayloadId"));
        Assert.assertEquals("没有载荷可比，不该报过期", Boolean.TRUE, capped.get("oldestPayloadReadable"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void selfStatSnapshot_timeRangeIsMinusOneWhenNoRows() {
        final Map<String, Object> h2 = (Map<String, Object>) storage.selfStatSnapshot().get("h2");
        Assert.assertEquals("无行时三端都应是 -1，不能是 MAX_VALUE（那个值会被前端当时间戳渲染）",
                Long.valueOf(-1L), h2.get("oldestStartTimeMs"));
        Assert.assertEquals(Long.valueOf(-1L), h2.get("newestStartTimeMs"));
        Assert.assertEquals(Long.valueOf(-1L), h2.get("coveredSpanMs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void selfStatSnapshot_oldestLiveRowRefreshesAfterRowCapEvictsIt() {
        storage.close();
        storage = new H2TraceSegmentStorage(true, 3, true, new File(cappedDir, "payload4.capped.db"), 1024L * 1024L);
        storage.clear();
        // 起始时间递增写：最老那条（start_time=1000）必被 1024 行后的水位清理掉
        for (int i = 1; i <= 1024; i++) {
            storage.storeLog(createLog("t" + i, "s" + i, 1000L * i, false));
        }

        final Map<String, Object> h2 = (Map<String, Object>) storage.selfStatSnapshot().get("h2");
        Assert.assertEquals("存活 3 段", Long.valueOf(3L), h2.get("rows"));
        Assert.assertEquals("最老存活段是第 1022 段（1000×1022），不是被清掉的第 1 段",
                Long.valueOf(1000L * 1022L), h2.get("oldestStartTimeMs"));
        Assert.assertEquals(Long.valueOf(1000L * 1024L), h2.get("newestStartTimeMs"));
        Assert.assertEquals(Long.valueOf(1000L * 1024L - 1000L * 1022L), h2.get("coveredSpanMs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void selfStatSnapshot_oldestPayloadIdTracksRingPointerAndStaysReadable() {
        for (int i = 1; i <= 3; i++) {
            storage.storeLog(createLog("t" + i, "s" + i, 1000L * i, false));
        }
        for (int i = 4; i <= 1024; i++) {
            storage.storeLog(createLog("t" + i, "s" + i, 1000L * i, false));
        }

        final Map<String, Object> snapshot = storage.selfStatSnapshot();
        final Map<String, Object> capped = (Map<String, Object>) snapshot.get("capped");
        final long oldestPayloadId = ((Long) capped.get("oldestPayloadId")).longValue();
        Assert.assertTrue("最早的载荷指针应指向真实块（非 -1）", oldestPayloadId >= 0L);
        Assert.assertEquals("刚写完、环还没绕圈 → 最早指针必在可读窗口内",
                Boolean.TRUE, capped.get("oldestPayloadReadable"));
        Assert.assertEquals(Boolean.TRUE, ((Map<String, Object>) capped.get("stats")).get("ioErrorCount")
                .equals(Long.valueOf(0L)));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void selfStatSnapshot_payloadExpiredAfterRingWrapsPastOldestPointer() {
        // 4096B 的环：1024 段 × 约 100B gzip ≈ 100KB，必然绕圈 20 圈以上，把最早的载荷覆盖掉。
        // 必须写满 1024 行才触发一次水位刷新（每 1024 行一次），否则 oldestLivePayloadId 还是初始值。
        storage.close();
        storage = new H2TraceSegmentStorage(true, 2000, true, new File(cappedDir, "tiny.capped.db"), 4096L);
        storage.clear();
        for (int i = 1; i <= 1024; i++) {
            storage.storeLog(createLog("t" + i, "s" + i, 1000L * i, false));
        }

        final Map<String, Object> capped = (Map<String, Object>) storage.selfStatSnapshot().get("capped");
        Assert.assertEquals("1024 段必然把 4096B 的环绕过多圈", Boolean.TRUE,
                Boolean.valueOf(((Long) capped.get("wrapCount")).longValue() >= 1L));
        Assert.assertTrue("最早的载荷指针应是真实偏移", ((Long) capped.get("oldestPayloadId")).longValue() >= 0L);
        Assert.assertEquals("绕圈后最早指针落在可读窗口左侧 → 报过期（预期结果，不是故障）",
                Boolean.FALSE, capped.get("oldestPayloadReadable"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void clear_resetsTimeRangeWatermarks() {
        storage.storeLog(createLog("t1", "s1", 1000L, false));
        storage.clear();

        final Map<String, Object> h2 = (Map<String, Object>) storage.selfStatSnapshot().get("h2");
        Assert.assertEquals("clear 后最老段时间必须回到 -1", Long.valueOf(-1L), h2.get("oldestStartTimeMs"));
        Assert.assertEquals("clear 后最新段时间必须回到 -1", Long.valueOf(-1L), h2.get("newestStartTimeMs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void selfStatSnapshot_disabledStorage_reportsDisabledShapes() {
        storage.close();
        storage = new H2TraceSegmentStorage(false, 2000, false, null, 0L);

        final Map<String, Object> snapshot = storage.selfStatSnapshot();
        final Map<String, Object> h2 = (Map<String, Object>) snapshot.get("h2");
        final Map<String, Object> capped = (Map<String, Object>) snapshot.get("capped");
        Assert.assertEquals(Boolean.FALSE, h2.get("enabled"));
        Assert.assertEquals(Boolean.FALSE, capped.get("enabled"));
        Assert.assertEquals("未启用时不应有任何行", Long.valueOf(0L), h2.get("rows"));
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
        span.setTagList(new ArrayList<Map<String, Object>>());
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
