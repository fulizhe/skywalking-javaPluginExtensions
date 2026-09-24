package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * 指标合并单测（Phase 5）：小时 rollup 与查询降采样。
 * <p>
 * 计数/总耗时/最大耗时精确、分位按请求数加权平均（近似）、跨 endpoint 隔离。
 * </p>
 */
public class TraceMetricsRollupTest {

    private static MetricsRow row(final String endpoint, final long bucket, final long req,
            final long err, final long slow, final long total, final long max,
            final int p50, final int p90, final int p95, final int p99, final int sample) {
        return new MetricsRow("svc", endpoint, bucket, req, err, slow, total, max, p50, p90, p95, p99, sample);
    }

    @Test
    public void toHourRows_sumsCountsAndWeightedPercentiles() {
        final List<MetricsRow> minuteRows = new ArrayList<MetricsRow>();
        minuteRows.add(row("GET:/a", 100L, 1L, 0L, 0L, 10L, 10L, 10, 20, 25, 30, 1));
        minuteRows.add(row("GET:/a", 101L, 3L, 1L, 1L, 60L, 40L, 30, 40, 45, 50, 3));
        minuteRows.add(row("*", 100L, 1L, 0L, 0L, 10L, 10L, 10, 20, 25, 30, 1));
        minuteRows.add(row("*", 101L, 3L, 1L, 1L, 60L, 40L, 30, 40, 45, 50, 3));

        final List<MetricsRow> hourRows = TraceMetricsRollup.toHourRows(minuteRows, 1L);
        Assert.assertEquals(2, hourRows.size());

        MetricsRow a = null, global = null;
        for (MetricsRow r : hourRows) {
            if ("GET:/a".equals(r.getEndpoint())) {
                a = r;
            }
            if ("*".equals(r.getEndpoint())) {
                global = r;
            }
        }
        Assert.assertNotNull(a);
        Assert.assertNotNull(global);
        Assert.assertEquals(1L, a.getTimeBucket());
        Assert.assertEquals(4L, a.getRequestCount());
        Assert.assertEquals(1L, a.getErrorCount());
        Assert.assertEquals(1L, a.getSlowCount());
        Assert.assertEquals(70L, a.getTotalLatency());
        Assert.assertEquals(40L, a.getMaxLatency());
        Assert.assertEquals(4, a.getSampleCount());
        // 加权平均：(10*1 + 30*3)/4 = 25
        Assert.assertEquals(25, a.getP50());
        Assert.assertEquals(40, a.getP95());
    }

    @Test
    public void toHourRows_missingPercentileYieldsNull() {
        final List<MetricsRow> minuteRows = new ArrayList<MetricsRow>();
        minuteRows.add(row("GET:/a", 100L, 1L, 0L, 0L, 10L, 10L, 10, 20, 25, 30, 1));
        minuteRows.add(row("GET:/a", 101L, 2L, 0L, 0L, 20L, 20L, 12, -1, -1, -1, 2));

        final List<MetricsRow> hourRows = TraceMetricsRollup.toHourRows(minuteRows, 1L);
        Assert.assertEquals(1, hourRows.size());
        Assert.assertEquals(-1, hourRows.get(0).getP90());
        Assert.assertEquals(3L, hourRows.get(0).getRequestCount());
    }

    @Test
    public void downsample_capsRowsAndPreservesTotals() {
        final List<MetricsRow> rows = new ArrayList<MetricsRow>();
        long totalReq = 0L;
        for (long i = 0L; i < 20L; i++) {
            rows.add(row("GET:/a", i, 2L, 0L, 0L, 20L, 20L, 10, 20, 25, 30, 2));
            totalReq += 2L;
        }
        final List<MetricsRow> out = TraceMetricsRollup.downsample(rows, 5);
        Assert.assertTrue("降采样后行数受控", out.size() <= 5);
        long req = 0L;
        for (MetricsRow r : out) {
            req += r.getRequestCount();
        }
        Assert.assertEquals("请求总数守恒", totalReq, req);
    }

    @Test
    public void downsample_noopWhenUnderTarget() {
        final List<MetricsRow> rows = new ArrayList<MetricsRow>();
        rows.add(row("GET:/a", 0L, 1L, 0L, 0L, 10L, 10L, 10, -1, -1, -1, 1));
        Assert.assertSame(rows, TraceMetricsRollup.downsample(rows, 100));
    }
}
