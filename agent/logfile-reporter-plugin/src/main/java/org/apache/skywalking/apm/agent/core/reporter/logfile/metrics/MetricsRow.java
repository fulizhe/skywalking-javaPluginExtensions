package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一行分钟聚合指标（service × endpoint × time_bucket）。
 * <p>
 * 分位数为 {@code -1} 表示该位未算（样本不足）；{@link #toMap()} 会转为 {@code null}。
 * </p>
 */
public class MetricsRow {

    private final String service;
    private final String endpoint;
    private final long timeBucket;
    private final long requestCount;
    private final long errorCount;
    private final long slowCount;
    private final long totalLatency;
    private final long maxLatency;
    private final int p50;
    private final int p90;
    private final int p95;
    private final int p99;
    private final int sampleCount;

    public MetricsRow(final String service, final String endpoint, final long timeBucket, final long requestCount,
            final long errorCount, final long slowCount, final long totalLatency, final long maxLatency,
            final int p50, final int p90, final int p95, final int p99, final int sampleCount) {
        this.service = service;
        this.endpoint = endpoint;
        this.timeBucket = timeBucket;
        this.requestCount = requestCount;
        this.errorCount = errorCount;
        this.slowCount = slowCount;
        this.totalLatency = totalLatency;
        this.maxLatency = maxLatency;
        this.p50 = p50;
        this.p90 = p90;
        this.p95 = p95;
        this.p99 = p99;
        this.sampleCount = sampleCount;
    }

    public String getService() {
        return service;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public long getTimeBucket() {
        return timeBucket;
    }

    public long getRequestCount() {
        return requestCount;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public long getSlowCount() {
        return slowCount;
    }

    public long getTotalLatency() {
        return totalLatency;
    }

    public long getMaxLatency() {
        return maxLatency;
    }

    public int getP50() {
        return p50;
    }

    public int getP90() {
        return p90;
    }

    public int getP95() {
        return p95;
    }

    public int getP99() {
        return p99;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public double getErrorRate() {
        return requestCount == 0L ? 0d : (double) errorCount / (double) requestCount;
    }

    public double getSlowRate() {
        return requestCount == 0L ? 0d : (double) slowCount / (double) requestCount;
    }

    public long getAvgLatency() {
        return requestCount == 0L ? 0L : totalLatency / requestCount;
    }

    /** JDK 原生 Map（供宿主工具类/大屏消费；分位缺失为 null）。 */
    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("service", service);
        map.put("endpoint", endpoint);
        map.put("timeBucket", timeBucket);
        map.put("requestCount", requestCount);
        map.put("errorCount", errorCount);
        map.put("slowCount", slowCount);
        map.put("errorRate", getErrorRate());
        map.put("slowRate", getSlowRate());
        map.put("avgLatency", getAvgLatency());
        map.put("maxLatency", maxLatency);
        map.put("p50", p50 < 0 ? null : Integer.valueOf(p50));
        map.put("p90", p90 < 0 ? null : Integer.valueOf(p90));
        map.put("p95", p95 < 0 ? null : Integer.valueOf(p95));
        map.put("p99", p99 < 0 ? null : Integer.valueOf(p99));
        map.put("sampleCount", sampleCount);
        return map;
    }
}
