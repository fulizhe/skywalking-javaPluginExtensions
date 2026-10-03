package org.apache.skywalking.apm.agent.core.reporter.logfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

/**
 * 内存热层统计（{@link LogFileTraceSegmentServiceClient#hotLayerSnapshot}）单测。
 *
 * <p>它是纯静态函数、不碰 SkyWalking 容器，所以能直接new 出快照喂进去——
 * 与 {@code isOrphanSegment} 同一套路（客户端本身要 bootstrap 才造得出来）。</p>
 *
 * <p>结构来源：{@code KeyedLocalStore<traceId, Map>}，value 是 {@code LogCollection.toMap()} 的结果
 * {@code {"logs": [{"spans": [{"startTime": …}, …]}, …]}}。</p>
 */
public class LogFileTraceSegmentServiceClientHotLayerTest {

    /** 造一条 trace 的 value：{@code segmentCount} 个段，每段一个 span，起始时间递增 1000ms。 */
    private static Map<String, Object> trace(final long firstStartMs, final int segmentCount) {
        final List<Map<String, Object>> logs = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < segmentCount; i++) {
            final Map<String, Object> span = new LinkedHashMap<String, Object>(2);
            span.put("spanId", Integer.valueOf(i));
            span.put("startTime", Long.valueOf(firstStartMs + 1000L * i));
            final List<Map<String, Object>> spans = new ArrayList<Map<String, Object>>(1);
            spans.add(span);
            final Map<String, Object> log = new LinkedHashMap<String, Object>(2);
            log.put("spans", spans);
            logs.add(log);
        }
        final Map<String, Object> entry = new LinkedHashMap<String, Object>(2);
        entry.put("logs", logs);
        return entry;
    }

    private static Map<String, Map<String, Object>> store(final Object... traceIdThenEntry) {
        final Map<String, Map<String, Object>> m = new LinkedHashMap<String, Map<String, Object>>();
        for (int i = 0; i < traceIdThenEntry.length; i += 2) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> entry = (Map<String, Object>) traceIdThenEntry[i + 1];
            m.put((String) traceIdThenEntry[i], entry);
        }
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void emptyStore_reportsZeroAndMinusOneTimes() {
        final Map<String, Object> m = LogFileTraceSegmentServiceClient.hotLayerSnapshot(
                new LinkedHashMap<String, Map<String, Object>>(), 1000);

        Assert.assertEquals(Boolean.TRUE, m.get("enabled"));
        Assert.assertEquals(Integer.valueOf(0), m.get("traces"));
        Assert.assertEquals(Long.valueOf(0L), m.get("segments"));
        Assert.assertEquals(Long.valueOf(0L), m.get("spans"));
        Assert.assertEquals("没有 span 时时间必须是 -1，不能是 0（0 会被页面当成 1970）",
                Long.valueOf(-1L), m.get("oldestSpanTimeMs"));
        Assert.assertEquals(Long.valueOf(-1L), m.get("newestSpanTimeMs"));
        Assert.assertEquals(Long.valueOf(-1L), m.get("coveredSpanMs"));
        Assert.assertEquals(Long.valueOf(0L), m.get("estimatedBytes"));
        Assert.assertEquals("容量上限要透出，页面靠它算「离淘汰还有多远」",
                Integer.valueOf(1000), m.get("maxTraces"));
    }

    @Test
    public void nullSnapshot_reportsDisabled() {
        final Map<String, Object> m = LogFileTraceSegmentServiceClient.hotLayerSnapshot(null, 1000);

        Assert.assertEquals(Boolean.FALSE, m.get("enabled"));
        Assert.assertEquals(Integer.valueOf(0), m.get("traces"));
        Assert.assertEquals(Long.valueOf(-1L), m.get("coveredSpanMs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void singleTrace_spansTimesAreMinAndMaxAcrossAllSpans() {
        // 一条 trace 两个段：第 0 段 span 在 1000，第 1 段 span 在 2000
        final Map<String, Object> m = LogFileTraceSegmentServiceClient.hotLayerSnapshot(
                store("t1", trace(1000L, 2)), 1000);

        Assert.assertEquals(Integer.valueOf(1), m.get("traces"));
        Assert.assertEquals("2 个段各 1 个 span", Long.valueOf(2L), m.get("segments"));
        Assert.assertEquals(Long.valueOf(2L), m.get("spans"));
        Assert.assertEquals(Long.valueOf(1000L), m.get("oldestSpanTimeMs"));
        Assert.assertEquals(Long.valueOf(2000L), m.get("newestSpanTimeMs"));
        Assert.assertEquals(Long.valueOf(1000L), m.get("coveredSpanMs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void multipleTraces_coveredSpanSpansTheWholeStore() {
        // 覆盖跨度必须跨全部存活 trace，而不是只看某一条
        final Map<String, Object> m = LogFileTraceSegmentServiceClient.hotLayerSnapshot(
                store("t1", trace(5000L, 1), "t2", trace(1000L, 1), "t3", trace(9000L, 1)), 1000);

        Assert.assertEquals(Integer.valueOf(3), m.get("traces"));
        Assert.assertEquals(Long.valueOf(1000L), m.get("oldestSpanTimeMs"));
        Assert.assertEquals(Long.valueOf(9000L), m.get("newestSpanTimeMs"));
        Assert.assertEquals(Long.valueOf(8000L), m.get("coveredSpanMs"));
    }

    /**
     * 残缺 trace：一条 trace 被 FIFO 淘汰后，后续段重新开一个新条目，
     * 于是同一条逻辑链路在热层里只剩最后一轮的段。这条测的是「我们如实统计，
     * 但无法识别残缺」——页面口径说明里必须写明这一点。
     */
    @Test
    @SuppressWarnings("unchecked")
    public void truncatedTrace_isCountedAsIs_withoutBeingFlagged() {
        final Map<String, Object> m = LogFileTraceSegmentServiceClient.hotLayerSnapshot(
                store("t1", trace(7000L, 1)), 1000);

        Assert.assertEquals(Integer.valueOf(1), m.get("traces"));
        Assert.assertEquals("只剩淘汰后重新写入的那一段", Long.valueOf(1L), m.get("segments"));
        Assert.assertEquals(Long.valueOf(7000L), m.get("oldestSpanTimeMs"));
        Assert.assertEquals("没有 truncated 之类的标记——这是已知缺陷，本次刻意不修",
                null, m.get("truncated"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void malformedValues_areSkippedWithoutThrowing() {
        // 历史数据结构可能缺字段/类型不对（老版本 map、null、字符串时间），读口不能因此抛异常
        final Map<String, Object> badSpan = new LinkedHashMap<String, Object>(2);
        badSpan.put("startTime", "not-a-number");            // 字符串时间
        final Map<String, Object> noTimeSpan = new LinkedHashMap<String, Object>(1);
        noTimeSpan.put("spanId", Integer.valueOf(9));         // 缺 startTime
        final Map<String, Object> nullTimeSpan = new LinkedHashMap<String, Object>(1);
        nullTimeSpan.put("startTime", null);                  // null 时间
        final Map<String, Object> zeroTimeSpan = new LinkedHashMap<String, Object>(1);
        zeroTimeSpan.put("startTime", Long.valueOf(0L));      // 0 不是有效 epoch ms

        final List<Map<String, Object>> spans = new ArrayList<Map<String, Object>>();
        Collections.addAll(spans, badSpan, noTimeSpan, nullTimeSpan, zeroTimeSpan);
        final Map<String, Object> log = new LinkedHashMap<String, Object>(1);
        log.put("spans", spans);
        final List<Map<String, Object>> logs = new ArrayList<Map<String, Object>>(1);
        logs.add(log);
        final Map<String, Object> entry = new LinkedHashMap<String, Object>(1);
        entry.put("logs", logs);

        final Map<String, Object> m = LogFileTraceSegmentServiceClient.hotLayerSnapshot(
                store("t1", entry, "t2", null), 1000);

        // traces 取 snapshot.size()（key 数），不是"数出来的 entry 数"：
        // 一个 null value 的 key 仍然占着容量，页面显示的"存活 trace"必须与容量口径一致。
        Assert.assertEquals("traces 是 key 数，null value 的 key 也算", Integer.valueOf(2), m.get("traces"));
        Assert.assertEquals("span 仍然计数，只是时间不参与", Long.valueOf(4L), m.get("spans"));
        Assert.assertEquals("没有一个有效时间 → -1", Long.valueOf(-1L), m.get("oldestSpanTimeMs"));
        Assert.assertEquals(Long.valueOf(-1L), m.get("coveredSpanMs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void missingLogsOrSpansKey_isTolerated() {
        final Map<String, Object> noLogs = new LinkedHashMap<String, Object>(1);
        noLogs.put("somethingElse", "x");
        final Map<String, Object> logWithoutSpans = new LinkedHashMap<String, Object>(1);
        logWithoutSpans.put("traceSegmentId", "s1");

        final List<Map<String, Object>> logs = new ArrayList<Map<String, Object>>(1);
        logs.add(logWithoutSpans);
        final Map<String, Object> entryWithBadLog = new LinkedHashMap<String, Object>(1);
        entryWithBadLog.put("logs", logs);

        final Map<String, Object> m = LogFileTraceSegmentServiceClient.hotLayerSnapshot(
                store("t1", noLogs, "t2", entryWithBadLog), 1000);

        Assert.assertEquals(Integer.valueOf(2), m.get("traces"));
        Assert.assertEquals("段计数不因缺 spans 而漏掉", Long.valueOf(1L), m.get("segments"));
        Assert.assertEquals(Long.valueOf(0L), m.get("spans"));
        Assert.assertEquals(Long.valueOf(-1L), m.get("oldestSpanTimeMs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void estimatedBytes_isRoughButProportionalToSegmentsAndSpans() {
        // 1 段 1 span → 800 + 200
        Assert.assertEquals(Long.valueOf(1000L),
                LogFileTraceSegmentServiceClient.hotLayerSnapshot(store("t1", trace(1000L, 1)), 1000)
                        .get("estimatedBytes"));
        // 3 段各 1 span → 3×800 + 3×200
        Assert.assertEquals(Long.valueOf(3000L),
                LogFileTraceSegmentServiceClient.hotLayerSnapshot(store("t1", trace(1000L, 3)), 1000)
                        .get("estimatedBytes"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void emptyStore_estimatedBytesIsZeroNotNegative() {
        Assert.assertEquals(Long.valueOf(0L),
                LogFileTraceSegmentServiceClient.hotLayerSnapshot(
                        new LinkedHashMap<String, Map<String, Object>>(), 1000).get("estimatedBytes"));
    }
}