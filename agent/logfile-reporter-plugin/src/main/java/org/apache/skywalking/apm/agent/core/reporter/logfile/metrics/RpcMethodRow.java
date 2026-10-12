package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个<b>出站 RPC 方法</b>（{@code componentId × operationName}）的<b>进程累计</b>行：
 * 自进程启动以来，它被调了多少次、错多少次、耗时分布如何、首末次出现在哪一分钟。
 * <p>
 * 与 {@link EdgeRow} / {@link EdgeLifetimeRow} 并列但<b>刻意不共用</b>：
 * <ul>
 *   <li>键是「组件 × 方法」，比边（入口端点 × 组件）少一维、比组件累计多一维方法；</li>
 *   <li>进程累计、纯内存、不落库、重启即失（同 {@link EdgeLifetimeRow} 的语义）；</li>
 *   <li>无 slow 判定——本榜按分位排序，不引入第二套慢阈值口径。</li>
 * </ul>
 * <p>
 * 跨 ClassLoader 只返回 JDK 原生类型，故只暴露 {@link #toMap()}。
 */
public class RpcMethodRow {

    private final int componentId;
    private final String spanLayer;
    private final String operation;
    private final long requestCount;
    private final long errorCount;
    private final long totalLatency;
    private final long maxLatency;
    private final int p50;
    private final int p95;
    private final int p99;
    private final long firstSeenBucket;
    private final long lastSeenBucket;
    private final int sampleCount;

    public RpcMethodRow(final int componentId, final String spanLayer, final String operation,
            final long requestCount, final long errorCount, final long totalLatency, final long maxLatency,
            final int p50, final int p95, final int p99, final long firstSeenBucket, final long lastSeenBucket,
            final int sampleCount) {
        this.componentId = componentId;
        this.spanLayer = spanLayer;
        this.operation = operation;
        this.requestCount = requestCount;
        this.errorCount = errorCount;
        this.totalLatency = totalLatency;
        this.maxLatency = maxLatency;
        this.p50 = p50;
        this.p95 = p95;
        this.p99 = p99;
        this.firstSeenBucket = firstSeenBucket;
        this.lastSeenBucket = lastSeenBucket;
        this.sampleCount = sampleCount;
    }

    public int getComponentId() {
        return componentId;
    }

    /** 出口 span 的 {@code spanLayer}（本榜恒为 {@code RPCFramework}；随行透出，供宿主侧命名 fallback）。 */
    public String getSpanLayer() {
        return spanLayer;
    }

    public String getOperation() {
        return operation;
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

    public int getP95() {
        return p95;
    }

    public int getP99() {
        return p99;
    }

    public long getFirstSeenBucket() {
        return firstSeenBucket;
    }

    public long getLastSeenBucket() {
        return lastSeenBucket;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    /** 错误率（0~1）；无调用时为 0。宿主侧着色用。 */
    public double errorRate() {
        return requestCount <= 0L ? 0d : (double) errorCount / (double) requestCount;
    }

    /** 平均耗时（毫秒）；无调用时为 0。注意：是<b>真平均</b>（总耗时 ÷ 次数），不是 P50。 */
    public double avgLatency() {
        return requestCount <= 0L ? 0d : (double) totalLatency / (double) requestCount;
    }

    /**
     * 转成读口可透出的行。组件名<b>不在这里翻译</b>（插件侧不打包组件库），
     * 由宿主侧用 {@code component-libraries.yml} 补（与 {@code /inner/sw/topology} 同一套三级 fallback）。
     */
    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("componentId", componentId);
        map.put("spanLayer", spanLayer);
        map.put("operation", operation);
        map.put("requestCount", requestCount);
        map.put("errorCount", errorCount);
        map.put("errorRate", errorRate());
        map.put("totalLatency", totalLatency);
        map.put("avgLatency", avgLatency());
        map.put("maxLatency", maxLatency);
        map.put("p50", p50);
        map.put("p95", p95);
        map.put("p99", p99);
        map.put("firstSeenBucket", firstSeenBucket);
        map.put("lastSeenBucket", lastSeenBucket);
        map.put("sampleCount", sampleCount);
        return map;
    }

    @Override
    public String toString() {
        return "RpcMethodRow{component:" + componentId + " op:" + operation + " n:" + requestCount + " err:"
                + errorCount + " p50:" + p50 + " p95:" + p95 + " max:" + maxLatency + "}";
    }
}
