package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条依赖边的聚合行：(入口端点, 组件, 分钟桶) → 四个量。
 * <p>
 * 与 {@link MetricsRow} 并列但**刻意不共用**：端点行按「请求」记（一次事务一个样本），
 * 依赖边按「出口调用」记（一次事务可能产生多条边），两者样本密度与基数量级都不同。
 * </p>
 * <p>
 * 无 slow 判定——慢的根因判定已在端点级由慢规则完成，边不引入第二套阈值口径。
 * </p>
 */
public class EdgeRow {

    private final String endpoint;
    private final int componentId;
    private final String spanLayer;
    private final List<String> operations;
    private final long timeBucket;
    private final long requestCount;
    private final long errorCount;
    private final long totalLatency;
    private final long maxLatency;
    private final int p50;
    private final int p90;
    private final int p95;
    private final int p99;
    private final int sampleCount;
    private final boolean operationsTruncated;

    public EdgeRow(final String endpoint, final int componentId, final String spanLayer,
            final List<String> operations, final long timeBucket, final long requestCount, final long errorCount,
            final long totalLatency, final long maxLatency, final int p50, final int p90, final int p95,
            final int p99, final int sampleCount, final boolean operationsTruncated) {
        this.endpoint = endpoint;
        this.componentId = componentId;
        this.spanLayer = spanLayer;
        this.operations = operations;
        this.timeBucket = timeBucket;
        this.requestCount = requestCount;
        this.errorCount = errorCount;
        this.totalLatency = totalLatency;
        this.maxLatency = maxLatency;
        this.p50 = p50;
        this.p90 = p90;
        this.p95 = p95;
        this.p99 = p99;
        this.sampleCount = sampleCount;
        this.operationsTruncated = operationsTruncated;
    }

    /** 常规构造（操作名未被截断）。 */
    public EdgeRow(final String endpoint, final int componentId, final String spanLayer,
            final List<String> operations, final long timeBucket, final long requestCount, final long errorCount,
            final long totalLatency, final long maxLatency, final int p50, final int p90, final int p95,
            final int p99, final int sampleCount) {
        this(endpoint, componentId, spanLayer, operations, timeBucket, requestCount, errorCount, totalLatency,
                maxLatency, p50, p90, p95, p99, sampleCount, false);
    }

    public String getEndpoint() {
        return endpoint;
    }

    public int getComponentId() {
        return componentId;
    }

    /**
     * 出口 span 的 {@code spanLayer}（Http / Database / RPCFramework …）。
     * <p>
     * 组件名在宿主侧翻译，而组件库并不覆盖全部插件注册的组件（实测 hutool-http 的
     * componentId=128 就查不到，见 docs/notes/2026-09-30-exit-span-runtime-probe.md）。
     * 宿主侧的 fallback 需要它，故随边透出。
     * </p>
     */
    public String getSpanLayer() {
        return spanLayer;
    }

    /**
     * 该边上出现过的出口操作名（去重，**已截断**）。
     * <p>
     * 明细档的依赖节点标签用它。刻意<b>不</b>把操作名放进边键——那会让边数乘上操作名基数；
     * 实测操作名已被 agent 归一化（DB 侧塌缩成 {@code execute}/{@code executeQuery} 两类，
     * 见 docs/notes/2026-09-30-exit-span-runtime-probe.md），但仍不假设它永远有界。
     * </p>
     */
    public List<String> getOperations() {
        return operations;
    }

    /** 操作名是否因超过上限而被截断。 */
    public boolean isOperationsTruncated() {
        return operationsTruncated;
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

    /** 错误率（0~1）；无样本时为 0。宿主侧着色用，避免各处重复算。 */
    public double errorRate() {
        return requestCount <= 0L ? 0d : (double) errorCount / (double) requestCount;
    }

    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("endpoint", endpoint);
        map.put("componentId", componentId);
        map.put("spanLayer", spanLayer);
        map.put("operations", operations);
        map.put("operationsTruncated", operationsTruncated);
        map.put("timeBucket", timeBucket);
        map.put("requestCount", requestCount);
        map.put("errorCount", errorCount);
        map.put("errorRate", errorRate());
        map.put("totalLatency", totalLatency);
        map.put("maxLatency", maxLatency);
        map.put("p50", p50);
        map.put("p90", p90);
        map.put("p95", p95);
        map.put("p99", p99);
        map.put("sampleCount", sampleCount);
        return map;
    }

    @Override
    public String toString() {
        return "EdgeRow{" + endpoint + " -> component:" + componentId + " bucket:" + timeBucket + " n:"
                + requestCount + " err:" + errorCount + " p50:" + p50 + " max:" + maxLatency + "}";
    }
}
