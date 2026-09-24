package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import org.junit.Assert;
import org.junit.Test;

/** 查询范围路由与行数上限单测（Phase 5）。 */
public class TraceMetricsQueryTest {

    @Test
    public void routeResolution_minuteWithin24h() {
        Assert.assertEquals("minute", TraceMetricsQuery.routeResolution(0L, 1440L));
        Assert.assertEquals("minute", TraceMetricsQuery.routeResolution(100L, 1060L));
    }

    @Test
    public void routeResolution_hourBeyond24h() {
        Assert.assertEquals("hour", TraceMetricsQuery.routeResolution(0L, 1441L));
        Assert.assertEquals("hour", TraceMetricsQuery.routeResolution(0L, 10080L));
    }

    @Test
    public void clampLimit_defaultsAndBounds() {
        Assert.assertEquals(TraceMetricsQuery.LIMIT_DEFAULT, TraceMetricsQuery.clampLimit(null));
        Assert.assertEquals(1, TraceMetricsQuery.clampLimit(0));
        Assert.assertEquals(500, TraceMetricsQuery.clampLimit(500));
        Assert.assertEquals(TraceMetricsQuery.LIMIT_MAX, TraceMetricsQuery.clampLimit(5000));
    }
}
