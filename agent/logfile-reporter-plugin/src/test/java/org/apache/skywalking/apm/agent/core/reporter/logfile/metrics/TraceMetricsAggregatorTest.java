package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;
import org.junit.Assert;
import org.junit.Test;

public class TraceMetricsAggregatorTest {

    private static final String SERVICE = "demo-app";
    private static final long SLOW_THRESHOLD_MS = 3000L;
    private static final long MINUTE_MS = 60_000L;

    private static final class CollectingSink implements MetricsSink {
        final List<MetricsRow> rows = new ArrayList<MetricsRow>();

        @Override
        public void store(final List<MetricsRow> batch) {
            rows.addAll(batch);
        }
    }

    private static long currentBucket() {
        return System.currentTimeMillis() / MINUTE_MS;
    }

    private static long bucketStart(final long bucket) {
        return bucket * MINUTE_MS;
    }

    private static SegmentObject entry(final String endpoint, final long start, final long end, final boolean error) {
        return SegmentObject.newBuilder().setTraceId("t").setTraceSegmentId("s").setService(SERVICE)
                .addSpans(SpanObject.newBuilder().setSpanId(0).setParentSpanId(-1).setOperationName(endpoint)
                        .setStartTime(start).setEndTime(end).setSpanType(SpanType.Entry).setIsError(error).build())
                .build();
    }

    private static SegmentObject entryWithTrace(final String traceId, final String endpoint, final long start,
            final long end, final boolean error) {
        return SegmentObject.newBuilder().setTraceId(traceId).setTraceSegmentId("s").setService(SERVICE)
                .addSpans(SpanObject.newBuilder().setSpanId(0).setParentSpanId(-1).setOperationName(endpoint)
                        .setStartTime(start).setEndTime(end).setSpanType(SpanType.Entry).setIsError(error).build())
                .build();
    }

    private static Map<String, Object> extreme(final List<Map<String, Object>> rows, final String endpoint) {
        for (Map<String, Object> r : rows) {
            if (endpoint.equals(r.get("endpoint"))) {
                return r;
            }
        }
        return null;
    }

    private static SegmentObject subSegment(final long start, final long end) {
        return SegmentObject.newBuilder().setTraceId("t").setTraceSegmentId("s").setService(SERVICE)
                .addSpans(SpanObject.newBuilder().setSpanId(0).setParentSpanId(0).setOperationName("Local work")
                        .setStartTime(start).setEndTime(end).setSpanType(SpanType.Local).build())
                .build();
    }

    /** 根 span（parentSpanId=-1）但非 Entry：模拟无上下文的 DB/pool 段或跨线程异步子段。 */
    private static SegmentObject rootSpan(final String op, final SpanType type, final long start, final long end) {
        return SegmentObject.newBuilder().setTraceId("t").setTraceSegmentId("s").setService(SERVICE)
                .addSpans(SpanObject.newBuilder().setSpanId(0).setParentSpanId(-1).setOperationName(op)
                        .setStartTime(start).setEndTime(end).setSpanType(type).build())
                .build();
    }

    private static MetricsRow row(final List<MetricsRow> rows, final String endpoint) {
        for (MetricsRow r : rows) {
            if (endpoint.equals(r.getEndpoint())) {
                return r;
            }
        }
        return null;
    }

    @Test
    public void entrySegmentCountedForEndpointAndGlobal() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long bucket = currentBucket();
        final long start = bucketStart(bucket) + 1000L;

        aggregator.onSegment(entry("GET:/a", start, start + 50L, false));
        aggregator.flushClosedBuckets(start + MINUTE_MS);

        final MetricsRow endpoint = row(sink.rows, "GET:/a");
        final MetricsRow global = row(sink.rows, TraceMetricsAggregator.GLOBAL_ENDPOINT);
        Assert.assertNotNull("endpoint row", endpoint);
        Assert.assertNotNull("global row", global);
        Assert.assertEquals(1L, endpoint.getRequestCount());
        Assert.assertEquals(50L, endpoint.getTotalLatency());
        Assert.assertEquals(50L, endpoint.getMaxLatency());
        Assert.assertEquals(1L, global.getRequestCount());
    }

    @Test
    public void nonEntrySegmentIgnored() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;

        aggregator.onSegment(subSegment(start, start + 30L));
        aggregator.flushClosedBuckets(start + MINUTE_MS);

        Assert.assertTrue("no rows for non-entry segment", sink.rows.isEmpty());
        Assert.assertEquals(1L, counters(aggregator).get("noEntrySpan"));
    }

    @Test
    public void rootLocalSpanSegmentIgnored() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;

        // 跨线程异步子段 / 无上下文操作：根 span 为 Local(parent=-1) → 不应计为事务
        aggregator.onSegment(rootSpan("HikariCP/Connection/close", SpanType.Local, start, start + 5L));
        aggregator.flushClosedBuckets(start + MINUTE_MS);

        Assert.assertTrue("root Local 不应计入", sink.rows.isEmpty());
        Assert.assertEquals(1L, counters(aggregator).get("noEntrySpan"));
    }

    @Test
    public void rootExitSpanSegmentIgnored() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;

        // 无上下文 DB/pool 操作：根 span 为 Exit(parent=-1) → 不应计为事务
        aggregator.onSegment(rootSpan("H2/JDBC/Connection/close", SpanType.Exit, start, start + 8L));
        aggregator.flushClosedBuckets(start + MINUTE_MS);

        Assert.assertTrue("root Exit 不应计入", sink.rows.isEmpty());
        Assert.assertEquals(1L, counters(aggregator).get("noEntrySpan"));
    }

    @Test
    public void normalOnlyContributesCountAndLatency() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;

        aggregator.onSegment(entry("GET:/n", start, start + 40L, false));
        aggregator.flushClosedBuckets(start + MINUTE_MS);

        final MetricsRow endpoint = row(sink.rows, "GET:/n");
        Assert.assertEquals(1L, endpoint.getRequestCount());
        Assert.assertEquals(0L, endpoint.getErrorCount());
        Assert.assertEquals(0L, endpoint.getSlowCount());
        Assert.assertEquals(40L, endpoint.getTotalLatency());
    }

    @Test
    public void errorAndSlowCounted() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;

        aggregator.onSegment(entry("GET:/e", start, start + SLOW_THRESHOLD_MS + 500L, true));
        aggregator.flushClosedBuckets(start + MINUTE_MS);

        final MetricsRow endpoint = row(sink.rows, "GET:/e");
        Assert.assertEquals(1L, endpoint.getErrorCount());
        Assert.assertEquals(1L, endpoint.getSlowCount());
    }

    @Test
    public void percentilesNearestRank() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;
        for (int i = 1; i <= 100; i++) {
            aggregator.onSegment(entry("GET:/p", start + i, start + i + i, false));
        }
        aggregator.flushClosedBuckets(start + MINUTE_MS);

        final MetricsRow global = row(sink.rows, TraceMetricsAggregator.GLOBAL_ENDPOINT);
        Assert.assertEquals(100, global.getSampleCount());
        Assert.assertEquals(50, global.getP50());
        Assert.assertEquals(90, global.getP90());
        Assert.assertEquals(95, global.getP95());
        Assert.assertEquals(99, global.getP99());
    }

    @Test
    public void smallSampleHasP50Only() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;

        aggregator.onSegment(entry("GET:/s", start, start + 42L, false));
        aggregator.flushClosedBuckets(start + MINUTE_MS);

        final MetricsRow global = row(sink.rows, TraceMetricsAggregator.GLOBAL_ENDPOINT);
        Assert.assertEquals(42, global.getP50());
        Assert.assertEquals(-1, global.getP90());
        Assert.assertEquals(-1, global.getP95());
        Assert.assertEquals(-1, global.getP99());
    }

    @Test
    public void globalRowAggregatesAllEndpoints() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;

        aggregator.onSegment(entry("GET:/a", start, start + 10L, false));
        aggregator.onSegment(entry("GET:/a", start + 1, start + 11L, false));
        aggregator.onSegment(entry("GET:/b", start + 2, start + 22L, false));
        aggregator.flushClosedBuckets(start + MINUTE_MS);

        Assert.assertEquals(2L, row(sink.rows, "GET:/a").getRequestCount());
        Assert.assertEquals(1L, row(sink.rows, "GET:/b").getRequestCount());
        Assert.assertEquals(3L, row(sink.rows, TraceMetricsAggregator.GLOBAL_ENDPOINT).getRequestCount());
    }

    @Test
    public void lateSegmentDropped() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long oldStart = bucketStart(currentBucket() - 10L) + 1000L;

        aggregator.onSegment(entry("GET:/late", oldStart, oldStart + 10L, false));
        aggregator.flushClosedBuckets(System.currentTimeMillis());

        Assert.assertTrue(sink.rows.isEmpty());
        Assert.assertEquals(1L, counters(aggregator).get("lateDropped"));
    }

    @Test
    public void bucketsBeyondWindowEvicted() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long bucket = currentBucket();
        final long start = bucketStart(bucket) + 1000L;

        aggregator.onSegment(entry("GET:/e", start, start + 10L, false));
        aggregator.flushClosedBuckets((bucket + 10L) * MINUTE_MS);

        Assert.assertTrue(sink.rows.isEmpty());
        Assert.assertTrue(buckets(aggregator).isEmpty());
    }

    @Test
    public void reservoirCapsSamples() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;
        for (int i = 0; i < 6000; i++) {
            aggregator.onSegment(entry("GET:/r", start + i, start + i + 5L, false));
        }
        aggregator.flushClosedBuckets(start + MINUTE_MS);

        final MetricsRow global = row(sink.rows, TraceMetricsAggregator.GLOBAL_ENDPOINT);
        Assert.assertEquals(6000L, global.getRequestCount());
        Assert.assertEquals(5000, global.getSampleCount());
        Assert.assertTrue((Long) counters(aggregator).get("sampleOverflow") > 0L);
    }

    @Test
    public void endpointOverflowMergesToOther() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;
        for (int i = 0; i < 501; i++) {
            aggregator.onSegment(entry("GET:/ep" + i, start + i, start + i + 5L, false));
        }
        aggregator.flushClosedBuckets(start + MINUTE_MS);

        Assert.assertEquals(1L, row(sink.rows, TraceMetricsAggregator.OTHER_ENDPOINT).getRequestCount());
        Assert.assertEquals(501L, row(sink.rows, TraceMetricsAggregator.GLOBAL_ENDPOINT).getRequestCount());
        Assert.assertEquals(1L, counters(aggregator).get("endpointOverflow"));
    }

    @Test
    public void extremeTraceKeepsMaxDurationPerEndpoint() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;

        aggregator.onSegment(entryWithTrace("t-slow", "GET:/x", start, start + 800L, false));
        aggregator.onSegment(entryWithTrace("t-fast", "GET:/x", start + 1, start + 20L, false));
        aggregator.onSegment(entryWithTrace("t-mid", "GET:/x", start + 2, start + 300L, false));

        final List<Map<String, Object>> extremes = aggregator.extremeTraces();
        Assert.assertEquals("每端点仅一条", 1, extremes.size());
        final Map<String, Object> x = extreme(extremes, "GET:/x");
        Assert.assertNotNull(x);
        Assert.assertEquals("t-slow", x.get("traceId"));
        Assert.assertEquals(800L, x.get("durationMs"));
        Assert.assertEquals(Boolean.FALSE, x.get("error"));
        Assert.assertEquals("max-duration", aggregator.getExtremeSelectorName());
    }

    @Test
    public void extremeTraceSeparatedPerEndpoint() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;

        aggregator.onSegment(entryWithTrace("a1", "GET:/a", start, start + 50L, false));
        aggregator.onSegment(entryWithTrace("b1", "GET:/b", start + 1, start + 900L, false));

        final List<Map<String, Object>> extremes = aggregator.extremeTraces();
        Assert.assertEquals(2, extremes.size());
        Assert.assertEquals("t-slow(b) 应排首位", "b1", extremes.get(0).get("traceId"));
        Assert.assertEquals("a1", extreme(extremes, "GET:/a").get("traceId"));
    }

    @Test
    public void extremeTraceIgnoresNonEntrySegment() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;

        aggregator.onSegment(subSegment(start, start + 9999L));

        Assert.assertTrue(aggregator.extremeTraces().isEmpty());
    }

    @Test
    public void extremeTraceCarriesErrorFlag() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;

        aggregator.onSegment(entryWithTrace("t-err", "GET:/e", start, start + 500L, true));

        Assert.assertEquals(Boolean.TRUE, extreme(aggregator.extremeTraces(), "GET:/e").get("error"));
    }

    @Test
    public void extremeTraceEndpointsBounded() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;
        for (int i = 0; i < 501; i++) {
            aggregator.onSegment(entryWithTrace("t" + i, "GET:/ex" + i, start + i, start + i + 5L, false));
        }

        Assert.assertEquals(500, aggregator.extremeTraces().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void snapshotExposesExtremesAndSelector() {
        final CollectingSink sink = new CollectingSink();
        final TraceMetricsAggregator aggregator = new TraceMetricsAggregator(sink, SERVICE, SLOW_THRESHOLD_MS);
        final long start = bucketStart(currentBucket()) + 1000L;

        aggregator.onSegment(entryWithTrace("t1", "GET:/s", start, start + 10L, false));

        final Map<String, Object> snapshot = aggregator.snapshot();
        Assert.assertEquals("max-duration", snapshot.get("extremeSelector"));
        Assert.assertEquals(1, ((List<Map<String, Object>>) snapshot.get("extremes")).size());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> counters(final TraceMetricsAggregator aggregator) {
        return (Map<String, Object>) aggregator.snapshot().get("counters");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buckets(final TraceMetricsAggregator aggregator) {
        return (List<Map<String, Object>>) aggregator.snapshot().get("buckets");
    }
}
