package org.apache.skywalking.apm.agent.core.reporter.logfile.storage;

import java.util.ArrayList;
import java.util.List;

import org.apache.skywalking.apm.agent.core.reporter.logfile.metrics.MetricsRow;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * H2TraceSegmentStorage 指标落库/查询单测（Phase 5）。
 * <p>
 * 只经 storeMetricRows → queryMetricRows 断言，不碰 SQL 文本；覆盖 MERGE 幂等、
 * endpoint / 范围查询、全局保留键 {@code "*"}、分位缺失、双分辨率隔离、保留期删除与降级。
 * </p>
 */
public class H2TraceSegmentStorageMetricsTest {

    private static final String SVC = "demo-app";

    private H2TraceSegmentStorage storage;

    @Before
    public void setUp() {
        storage = new H2TraceSegmentStorage(true, 2000);
        storage.clear();
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    private static MetricsRow row(final String endpoint, final long bucket, final long request,
            final long error, final long slow, final long total, final long max,
            final int p50, final int p90, final int p95, final int p99, final int sample) {
        return new MetricsRow(SVC, endpoint, bucket, request, error, slow, total, max, p50, p90, p95, p99, sample);
    }

    @Test
    public void storeAndQueryByEndpoint_orderedByBucket() {
        storage.storeMetricRows("minute", list(
                row("GET:/a", 100L, 3L, 0L, 1L, 60L, 40L, 20, 30, 35, 40, 3),
                row("GET:/a", 101L, 2L, 1L, 0L, 50L, 30L, 25, 28, 29, 30, 2)));

        final List<MetricsRow> rows = storage.queryMetricRows("minute", "GET:/a", 100L, 101L, 100);
        Assert.assertEquals(2, rows.size());
        Assert.assertEquals(100L, rows.get(0).getTimeBucket());
        Assert.assertEquals(101L, rows.get(1).getTimeBucket());
        Assert.assertEquals(3L, rows.get(0).getRequestCount());
        Assert.assertEquals(40L, rows.get(0).getMaxLatency());
        Assert.assertEquals(20, rows.get(0).getP50());
    }

    @Test
    public void mergeIsIdempotent_sameKeyOverwrites() {
        storage.storeMetricRows("minute", list(row("GET:/a", 100L, 3L, 0L, 0L, 30L, 20L, 10, 12, 13, 15, 3)));
        storage.storeMetricRows("minute", list(row("GET:/a", 100L, 9L, 2L, 1L, 90L, 50L, 30, 40, 45, 50, 9)));

        final List<MetricsRow> rows = storage.queryMetricRows("minute", "GET:/a", 100L, 100L, 100);
        Assert.assertEquals("MERGE 覆盖，不重复计数", 1, rows.size());
        Assert.assertEquals(9L, rows.get(0).getRequestCount());
        Assert.assertEquals(2L, rows.get(0).getErrorCount());
        Assert.assertEquals(50, rows.get(0).getP99());
    }

    @Test
    public void queryWithoutEndpoint_returnsAllIncludingGlobal() {
        storage.storeMetricRows("minute", list(
                row("GET:/a", 100L, 1L, 0L, 0L, 10L, 10L, 10, 10, 10, 10, 1),
                row("*", 100L, 1L, 0L, 0L, 10L, 10L, 10, 10, 10, 10, 1)));

        final List<MetricsRow> rows = storage.queryMetricRows("minute", null, 100L, 100L, 100);
        Assert.assertEquals(2, rows.size());
        boolean hasGlobal = false;
        for (MetricsRow r : rows) {
            if ("*".equals(r.getEndpoint())) {
                hasGlobal = true;
            }
        }
        Assert.assertTrue("应包含全局保留键 * 行", hasGlobal);
    }

    @Test
    public void missingPercentilesRoundTripAsMinusOne() {
        storage.storeMetricRows("minute", list(row("GET:/s", 100L, 1L, 0L, 0L, 42L, 42L, 42, -1, -1, -1, 1)));
        final List<MetricsRow> rows = storage.queryMetricRows("minute", "GET:/s", 100L, 100L, 100);
        Assert.assertEquals(1, rows.size());
        Assert.assertEquals(42, rows.get(0).getP50());
        Assert.assertEquals(-1, rows.get(0).getP90());
        Assert.assertEquals(-1, rows.get(0).getP99());
    }

    @Test
    public void resolutionIsolated_minuteVsHour() {
        storage.storeMetricRows("minute", list(row("GET:/a", 100L, 1L, 0L, 0L, 10L, 10L, 10, -1, -1, -1, 1)));
        storage.storeMetricRows("hour", list(row("GET:/a", 1L, 5L, 0L, 0L, 50L, 20L, 12, -1, -1, -1, 5)));

        Assert.assertEquals(1, storage.queryMetricRows("minute", "GET:/a", 0L, 1000L, 100).size());
        final List<MetricsRow> hour = storage.queryMetricRows("hour", "GET:/a", 0L, 1000L, 100);
        Assert.assertEquals(1, hour.size());
        Assert.assertEquals(5L, hour.get(0).getRequestCount());
    }

    @Test
    public void limitTruncatesResults() {
        final List<MetricsRow> batch = new ArrayList<MetricsRow>();
        for (long b = 0L; b < 10L; b++) {
            batch.add(row("GET:/a", b, 1L, 0L, 0L, 10L, 10L, 10, -1, -1, -1, 1));
        }
        storage.storeMetricRows("minute", batch);
        Assert.assertEquals(5, storage.queryMetricRows("minute", "GET:/a", 0L, 9L, 5).size());
    }

    @Test
    public void deleteBeforeRemovesOlderBuckets() {
        storage.storeMetricRows("minute", list(
                row("GET:/a", 1L, 1L, 0L, 0L, 10L, 10L, 10, -1, -1, -1, 1),
                row("GET:/a", 2L, 1L, 0L, 0L, 10L, 10L, 10, -1, -1, -1, 1),
                row("GET:/a", 3L, 1L, 0L, 0L, 10L, 10L, 10, -1, -1, -1, 1)));

        storage.deleteMetricsBefore("minute", 3L);
        final List<MetricsRow> rows = storage.queryMetricRows("minute", "GET:/a", 0L, 9L, 100);
        Assert.assertEquals(1, rows.size());
        Assert.assertEquals(3L, rows.get(0).getTimeBucket());
    }

    @Test
    public void aggregateByEndpoint_sumsAndWeightedPercentiles_noStarvation() {
        storage.storeMetricRows("minute", list(
                row("GET:/a", 100L, 1L, 0L, 0L, 10L, 10L, 10, -1, -1, -1, 1),
                row("GET:/a", 101L, 3L, 1L, 0L, 60L, 40L, 30, -1, -1, -1, 3),
                row("GET:/b", 100L, 5L, 0L, 0L, 50L, 20L, 20, -1, -1, -1, 5)));

        final List<MetricsRow> agg = storage.aggregateMetricRows("minute", 0L, 1000L, 100);
        Assert.assertEquals("两个端点都应出现(不被字典序饿死)", 2, agg.size());
        Assert.assertEquals("按请求数降序", "GET:/b", agg.get(0).getEndpoint());

        MetricsRow a = null;
        for (MetricsRow r : agg) {
            if ("GET:/a".equals(r.getEndpoint())) {
                a = r;
            }
        }
        Assert.assertNotNull(a);
        Assert.assertEquals(4L, a.getRequestCount());
        Assert.assertEquals(1L, a.getErrorCount());
        Assert.assertEquals(70L, a.getTotalLatency());
        Assert.assertEquals(40L, a.getMaxLatency());
        Assert.assertEquals(4, a.getSampleCount());
        Assert.assertEquals("加权平均 (10*1+30*3)/4", 25, a.getP50());
        Assert.assertEquals(-1, a.getP90());
    }

    @Test
    public void disabledStorage_returnsEmptyAndZero() {
        storage.close();
        storage = new H2TraceSegmentStorage(false, 2000, false, null, 0L);

        Assert.assertEquals(0, storage.storeMetricRows("minute",
                list(row("GET:/a", 1L, 1L, 0L, 0L, 10L, 10L, 10, -1, -1, -1, 1))));
        Assert.assertTrue(storage.queryMetricRows("minute", "GET:/a", 0L, 9L, 100).isEmpty());
        Assert.assertEquals(0L, storage.getErrorCount());
    }

    private static List<MetricsRow> list(final MetricsRow... rows) {
        final List<MetricsRow> out = new ArrayList<MetricsRow>();
        for (MetricsRow r : rows) {
            out.add(r);
        }
        return out;
    }
}
