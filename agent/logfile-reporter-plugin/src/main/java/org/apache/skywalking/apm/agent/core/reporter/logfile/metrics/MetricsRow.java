package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一行分钟聚合指标（endpoint × time_bucket）。
 * <p>
 * 分位数为 {@code -1} 表示该位未算（样本不足）；{@link #toMap()} 会转为 {@code null}。
 * </p>
 */
public class MetricsRow {

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
    /** 各桶对应分位的最大值（"最差分钟"口径）；{@code -1} 表示未算（非聚合行）。 */
    private final int worstP50;
    private final int worstP90;
    private final int worstP95;
    private final int worstP99;
    /** 有数据（request_count>0）的桶数；{@code -1} 表示未算（非聚合行）。 */
    private final int bucketCount;

    public MetricsRow(final String endpoint, final long timeBucket, final long requestCount,
            final long errorCount, final long slowCount, final long totalLatency, final long maxLatency,
            final int p50, final int p90, final int p95, final int p99, final int sampleCount) {
        this(endpoint, timeBucket, requestCount, errorCount, slowCount, totalLatency, maxLatency,
                p50, p90, p95, p99, sampleCount, -1, -1, -1, -1, -1);
    }

    /**
     * 全字段构造：非聚合行传 {@code worst*-1 / bucketCount=-1}；
     * 聚合读口行填入各桶分位最大值与有数据桶数（"最差分钟"口径）。
     */
    public MetricsRow(final String endpoint, final long timeBucket, final long requestCount,
            final long errorCount, final long slowCount, final long totalLatency, final long maxLatency,
            final int p50, final int p90, final int p95, final int p99, final int sampleCount,
            final int worstP50, final int worstP90, final int worstP95, final int worstP99, final int bucketCount) {
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
        this.worstP50 = worstP50;
        this.worstP90 = worstP90;
        this.worstP95 = worstP95;
        this.worstP99 = worstP99;
        this.bucketCount = bucketCount;
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

    public int getWorstP50() {
        return worstP50;
    }

    public int getWorstP90() {
        return worstP90;
    }

    public int getWorstP95() {
        return worstP95;
    }

    public int getWorstP99() {
        return worstP99;
    }

    public int getBucketCount() {
        return bucketCount;
    }

    /**
     * 该行自身即单桶时的"最差分钟"视图：{@code worst* = 自身分位}、{@code bucketCount = 1}（有请求时）。
     * 供聚合读口把尚未翻转的当前内存分钟并入 ③ 口径（否则应用刚启动、H2 未落桶时最差为空）。
     */
    public MetricsRow selfAsWorst() {
        return new MetricsRow(endpoint, timeBucket, requestCount, errorCount, slowCount, totalLatency, maxLatency,
                p50, p90, p95, p99, sampleCount,
                p50, p90, p95, p99, requestCount > 0L ? 1 : 0);
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

    /**
     * QPS = 请求数 / 桶跨度秒。跨度由调用方按**分辨率**给出（分钟行 60、小时行 3600、
     * 范围聚合行用查询范围跨度），因此本方法不写死 60；跨度 &le; 0 视为 0。
     */
    public double getQps(final long bucketSpanSeconds) {
        return bucketSpanSeconds <= 0L ? 0d : (double) requestCount / (double) bucketSpanSeconds;
    }

    /** JDK 原生 Map（供宿主工具类/大屏消费；分位缺失为 null）。 */
    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<String, Object>();
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
        map.put("worstP50", worstP50 < 0 ? null : Integer.valueOf(worstP50));
        map.put("worstP90", worstP90 < 0 ? null : Integer.valueOf(worstP90));
        map.put("worstP95", worstP95 < 0 ? null : Integer.valueOf(worstP95));
        map.put("worstP99", worstP99 < 0 ? null : Integer.valueOf(worstP99));
        map.put("bucketCount", bucketCount < 0 ? null : Integer.valueOf(bucketCount));
        return map;
    }
}
