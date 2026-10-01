package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个外部依赖的**进程生命周期累计**记录：自应用启动以来，它被调用过多少次、累计错多少次、
 * 第一次/最后一次出现的时间、以及出口操作名（去重、封顶）。
 *
 * <p><b>与 {@link EdgeRow} 的分工</b>：
 * <ul>
 *   <li>{@link EdgeRow} = <b>窗口内</b>的边（按分钟桶），只保留「当前分钟 + 3 个晚到桶」，
 *       用来回答"此刻谁在调谁、哪条路慢"。</li>
 *   <li>本类 = <b>自进程启动以来</b>的组件级累计，<b>不参与桶淘汰</b>，用来回答
 *       "这个进程从起来到现在，到底伸出去过哪些手"。</li>
 * </ul>
 *
 * <p><b>为什么必须在插件侧记</b>：窗口桶约 4 分钟就被淘汰，读口侧只剩最近几个桶，
 * 无法还原"自启动以来"的历史；而"见过哪些依赖"这个问题只需要组件粒度的低基数集合，
 * 内存代价可以忽略（见 {@code MAX_LIFETIME_COMPONENTS}）。
 *
 * <p><b>口径边界</b>：与边同源 —— 迟到丢弃的段（{@code lateDropped}）不计入，
 * 因为那类段的分钟桶已超出可接受范围；跨段（异步）出口同样不在内，与边的"只做段内配对"一致。
 *
 * <p>跨 ClassLoader 只返回 JDK 原生类型，故只暴露 {@link #toMap()}。
 */
public class EdgeLifetimeRow {

    private final int componentId;
    private final String spanLayer;
    private final List<String> operations;
    private final boolean operationsTruncated;
    private final long requestCount;
    private final long errorCount;
    private final long maxLatency;
    private final long firstSeenBucket;
    private final long lastSeenBucket;

    public EdgeLifetimeRow(final int componentId, final String spanLayer, final List<String> operations,
            final boolean operationsTruncated, final long requestCount, final long errorCount, final long maxLatency,
            final long firstSeenBucket, final long lastSeenBucket) {
        this.componentId = componentId;
        this.spanLayer = spanLayer;
        this.operations = operations;
        this.operationsTruncated = operationsTruncated;
        this.requestCount = requestCount;
        this.errorCount = errorCount;
        this.maxLatency = maxLatency;
        this.firstSeenBucket = firstSeenBucket;
        this.lastSeenBucket = lastSeenBucket;
    }

    public int getComponentId() {
        return componentId;
    }

    public String getSpanLayer() {
        return spanLayer;
    }

    public List<String> getOperations() {
        return operations;
    }

    public boolean isOperationsTruncated() {
        return operationsTruncated;
    }

    public long getRequestCount() {
        return requestCount;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public long getMaxLatency() {
        return maxLatency;
    }

    public long getFirstSeenBucket() {
        return firstSeenBucket;
    }

    public long getLastSeenBucket() {
        return lastSeenBucket;
    }

    /** 累计错误率（0~1）；无调用时为 0。宿主侧着色用。 */
    public double errorRate() {
        return requestCount <= 0L ? 0d : (double) errorCount / (double) requestCount;
    }

    /**
     * 转成读口可透出的行。组件名<b>不在这里翻译</b>——插件侧不打包组件库，
     * 由宿主侧用 {@code component-libraries.yml} 补（与 {@code /inner/sw/topology} 同一套逻辑）。
     *
     * <p>时间给两种形态：分钟桶（便于与边表对照）与毫秒时间戳（页面直接显示绝对时间）。
     */
    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("componentId", componentId);
        map.put("spanLayer", spanLayer);
        map.put("operations", new ArrayList<String>(operations));
        map.put("operationsTruncated", operationsTruncated);
        map.put("requestCount", requestCount);
        map.put("errorCount", errorCount);
        map.put("errorRate", errorRate());
        map.put("maxLatency", maxLatency);
        map.put("firstSeenBucket", firstSeenBucket);
        map.put("lastSeenBucket", lastSeenBucket);
        return map;
    }

    @Override
    public String toString() {
        return "EdgeLifetimeRow{component:" + componentId + " layer:" + spanLayer
                + " n:" + requestCount + " err:" + errorCount + " ops:" + operations.size() + "}";
    }
}