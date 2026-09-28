package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import org.junit.Assert;
import org.junit.Test;

/** {@link MetricsRow#getQps(long)} 分母口径单测（分钟 / 小时 / 范围跨度；不写死 60）。 */
public class MetricsRowTest {

    private static MetricsRow row(final long requestCount) {
        return new MetricsRow("GET:/a", 100L, requestCount, 0L, 0L, 0L, 0L, -1, -1, -1, -1, 0);
    }

    @Test
    public void qpsMinuteBucket() {
        Assert.assertEquals(2.0d, row(120L).getQps(60L), 0.0001d);
    }

    @Test
    public void qpsHourBucket() {
        Assert.assertEquals(0.5d, row(1800L).getQps(3600L), 0.0001d);
    }

    @Test
    public void qpsRangeSpan() {
        Assert.assertEquals(1.0d, row(120L).getQps(120L), 0.0001d);
    }

    @Test
    public void qpsZeroSpanIsZero() {
        Assert.assertEquals(0.0d, row(120L).getQps(0L), 0.0001d);
    }
}
