package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanLayer;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;

/**
 * RPC 调用排行聚合：把本 JVM <b>出站</b>的 RPC span（{@code spanLayer = RPCFramework}）
 * 按 <b>{@code componentId × operationName}</b> 做<b>进程累计</b>（自启动以来），供「RPC 调用排行」页。
 * <p>
 * <h3>为什么独立于依赖边</h3>
 * 依赖边（{@link EdgeMetricsAggregator}）的键是「入口端点 × 组件」的交叉积，为了内存把每键样本池压到 256；
 * 这里只按方法一维，基数远低，可给 {@link #MAX_SAMPLES_PER_METHOD} 的大样本池让分位更准，且
 * <b>完全不动既有边/清单的键与语义</b>。
 *
 * <h3>两个存储：分位池与证据集分开</h3>
 * <ul>
 *   <li><b>分位样本池</b>：每方法 2048 条，仅存 {@code duration}，用于算 P50/P95/P99/最大；</li>
 *   <li><b>证据集</b>：每方法 ≤{@link #EVIDENCE_SIZE}（最慢 1 条 + 蓄水池随机 4 条），各带
 *       {@code duration / 时点 / traceId / isError}，用于点行下钻链路。</li>
 * </ul>
 * 刻意<b>不合并</b>：把 traceId 塞进 2048 条池子会让每方法多出数十 KB × 方法数，代价不成比例。
 *
 * <h3>边界（页面与读口须写明）</h3>
 * <ul>
 *   <li><b>孤段不计</b>：喂入发生在段消费的孤段过滤之后，故无 Entry 且无 ref 的"无上下文 RPC"不进榜；</li>
 *   <li><b>入站不计</b>：本 JVM 当 RPC 服务端的 Entry 不进这张榜（已在 endpoint 指标里）；</li>
 *   <li>累计口径、纯内存、不落库、重启即失。</li>
 * </ul>
 * 纯内存、无 I/O；异常就地吞掉并计数。
 */
public class RpcMetricsAggregator {

    /** 只收这一层的出口 span。 */
    public static final String RPC_LAYER = "RPCFramework";
    /** 方法数超限后，后来的方法并进这一个"垃圾桶"（与依赖边的 (other) 对齐）。 */
    public static final String OTHER_OPERATION = "(other)";
    /** 兜底方法的组件号（保留值，正常不会出现于榜中）。 */
    public static final int OTHER_COMPONENT = -1;
    /** 出口 span 没有 operationName 时的兜底方法名。 */
    public static final String UNKNOWN_OPERATION = "(unknown)";

    /** 方法数上限：超出并入兜底方法并计 {@code methodOverflow}，保证内存有界。 */
    public static final int MAX_METHODS = 512;
    /** 每方法分位样本池上限（蓄水池抽样，满后随机顶替）。 */
    public static final int MAX_SAMPLES_PER_METHOD = 2048;
    /** 出 p95/p99 的最少样本数；不足只给 p50。与端点级/边级同口径。 */
    private static final int MIN_SAMPLES_FOR_TAILS = 20;
    /** 每方法证据集大小：最慢 1 + 随机 4。 */
    public static final int EVIDENCE_SIZE = 5;
    /** 证据集里的随机槽位数（= 证据集大小 − 最慢那条）。 */
    private static final int EVIDENCE_RANDOM_SLOTS = EVIDENCE_SIZE - 1;
    /** 抽样种子写死，使抽样过程可复现、测试可写。 */
    private static final long RESERVOIR_SEED = 20261012L;
    /** 分钟桶换算。 */
    private static final long MINUTE_MS = 60_000L;

    private final Object lock = new Object();
    /** 方法键 → 累计单元。用 LinkedHashMap 只为迭代顺序稳定，不依赖其含义。 */
    private final Map<RpcKey, MethodAccumulator> methods = new LinkedHashMap<RpcKey, MethodAccumulator>();
    /** 进程启动时刻（构造时刻），读口回给宿主显示"自启动以来"的起点。 */
    private final long startedAtMs = System.currentTimeMillis();
    /** 分位池与证据集共用的随机源（种子写死，整体序列可复现）。 */
    private final Random reservoirRandom = new Random(RESERVOIR_SEED);
    private final Map<String, Long> counters = new LinkedHashMap<String, Long>();
    private final int maxMethods;

    public RpcMetricsAggregator() {
        this(MAX_METHODS);
    }

    /** 单测用：注入更小的上限以验证溢出与有界性。 */
    static RpcMetricsAggregator forTesting(final int maxMethods) {
        return new RpcMetricsAggregator(maxMethods);
    }

    private RpcMetricsAggregator(final int maxMethods) {
        this.maxMethods = maxMethods;
    }

    /**
     * 喂入一条原始 segment：只取其中 {@code spanType = Exit} 且 {@code spanLayer = RPCFramework} 的 span。
     * 无此类出口不产行；异常就地吞掉并计数。
     * <p>
     * 与依赖边不同，这里<b>不要求段内有 Entry</b>：RPC 计数不需要配对方（不跨段），
     * 只要段本身不是孤段（由消费侧的孤段过滤保证）即可。
     * </p>
     */
    public void onSegment(final SegmentObject segment) {
        if (segment == null) {
            return;
        }
        try {
            final List<SpanObject> spans = segment.getSpansList();
            for (int i = 0; i < spans.size(); i++) {
                final SpanObject span = spans.get(i);
                if (!SpanType.Exit.equals(span.getSpanType())) {
                    continue;
                }
                if (!SpanLayer.RPCFramework.equals(span.getSpanLayer())) {
                    continue;
                }
                long duration = span.getEndTime() - span.getStartTime();
                if (duration < 0L) {
                    duration = 0L;
                }
                synchronized (lock) {
                    accumulate(span.getComponentId(), sanitizeOperation(span.getOperationName()), duration,
                            span.getIsError(), span.getStartTime(), segment.getTraceId());
                }
            }
        } catch (Exception e) {
            bump("aggregateErrors");
        }
    }

    /**
     * 方法排行榜快照：每方法一行，按 P95 降序（同为 0 时按调用次数降序、再按方法名升序兜底，避免刷新抖动）。
     * 返回防御性拷贝，调用方改动不影响内部状态。
     * <p>
     * <b>取舍</b>：分位在锁内现算（对 ≤{@link #MAX_METHODS} 行、每行 ≤{@link #MAX_SAMPLES_PER_METHOD} 样本排序）。
     * 曾评估"把样本拷出锁、在锁外算分位"，但那需要在锁内先复制一份样本数组，而 {@code percentiles()}
     * 本身已要复制+排序——等于每次读多一份分配，得不偿失。方法数有上限、demo 个位数，故维持现状。
     * </p>
     */
    public List<RpcMethodRow> snapshot() {
        final List<RpcMethodRow> out = new ArrayList<RpcMethodRow>();
        synchronized (lock) {
            for (Map.Entry<RpcKey, MethodAccumulator> entry : methods.entrySet()) {
                out.add(toRow(entry.getKey(), entry.getValue()));
            }
        }
        Collections.sort(out, new Comparator<RpcMethodRow>() {
            @Override
            public int compare(final RpcMethodRow a, final RpcMethodRow b) {
                final int byP95 = Integer.compare(b.getP95(), a.getP95());
                if (byP95 != 0) {
                    return byP95;
                }
                final int byCount = Long.compare(b.getRequestCount(), a.getRequestCount());
                if (byCount != 0) {
                    return byCount;
                }
                return a.getOperation().compareTo(b.getOperation());
            }
        });
        return out;
    }

    /**
     * 某方法的<b>证据样本</b>（最慢 1 条 + 随机 4 条，各带 traceId）。点行才拉，按需调用。
     * <p>
     * 兜底方法 {@code (other)} 返回空——它混了多个方法/组件，证据无意义；
     * 样本按 traceId 去重（避免同一 trace 既当最慢又当随机样本）。返回 JDK 原生类型。
     * </p>
     */
    public List<Map<String, Object>> evidence(final int componentId, final String operation) {
        final List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(EVIDENCE_SIZE);
        if (componentId == OTHER_COMPONENT || operation == null || OTHER_OPERATION.equals(operation)) {
            return out;
        }
        synchronized (lock) {
            final MethodAccumulator accumulator = methods.get(new RpcKey(componentId, operation));
            if (accumulator == null) {
                return out;
            }
            final Set<String> seenTraceIds = new HashSet<String>();
            if (accumulator.slowest != null) {
                out.add(accumulator.slowest.toMap(true));
                if (accumulator.slowest.traceId != null) {
                    seenTraceIds.add(accumulator.slowest.traceId);
                }
            }
            for (int i = 0; i < accumulator.randomSlots.length; i++) {
                final Evidence sample = accumulator.randomSlots[i];
                if (sample == null) {
                    continue;
                }
                if (sample.traceId != null && !seenTraceIds.add(sample.traceId)) {
                    continue;
                }
                out.add(sample.toMap(false));
            }
        }
        return out;
    }

    /** 进程启动时刻（毫秒）；读口回给宿主显示"自启动以来"的起点。 */
    public long getStartedAtMs() {
        return startedAtMs;
    }

    /** 体检计数：方法溢出 / 聚合异常 / 存活方法数。 */
    public Map<String, Object> snapshotCounters() {
        final Map<String, Object> out = new LinkedHashMap<String, Object>();
        synchronized (lock) {
            for (Map.Entry<String, Long> entry : counters.entrySet()) {
                out.put(entry.getKey(), entry.getValue());
            }
            out.put("methodOverflow", getOrZero("methodOverflow"));
            out.put("aggregateErrors", getOrZero("aggregateErrors"));
            out.put("liveMethods", (long) methods.size());
        }
        return out;
    }

    // ------------------------------------------------------------------ 内部

    private void accumulate(final int componentId, final String operation, final long duration, final boolean error,
            final long startTimeMs, final String traceId) {
        RpcKey key = new RpcKey(componentId, operation);
        MethodAccumulator accumulator = methods.get(key);
        if (accumulator == null && methods.size() >= maxMethods && !isOther(key)) {
            // 方法数触顶：并进兜底键（保留计数，不静默丢）
            bump("methodOverflow");
            key = otherKey();
            accumulator = methods.get(key);
        }
        if (accumulator == null) {
            accumulator = new MethodAccumulator();
            methods.put(key, accumulator);
        }
        accumulator.add(duration, error, startTimeMs, traceId, reservoirRandom);
    }

    private static boolean isOther(final RpcKey key) {
        return key.componentId == OTHER_COMPONENT || OTHER_OPERATION.equals(key.operation);
    }

    private static RpcKey otherKey() {
        return new RpcKey(OTHER_COMPONENT, OTHER_OPERATION);
    }

    private RpcMethodRow toRow(final RpcKey key, final MethodAccumulator accumulator) {
        final int[] percentiles = accumulator.percentiles();
        final long firstBucket = accumulator.firstSeenMs < 0L ? 0L : accumulator.firstSeenMs / MINUTE_MS;
        final long lastBucket = accumulator.lastSeenMs < 0L ? 0L : accumulator.lastSeenMs / MINUTE_MS;
        return new RpcMethodRow(key.componentId, RPC_LAYER, key.operation, accumulator.requestCount,
                accumulator.errorCount, accumulator.totalLatency, accumulator.maxLatency, percentiles[0],
                percentiles[1], percentiles[2], firstBucket, lastBucket, accumulator.sampleCount);
    }

    private void bump(final String name) {
        synchronized (lock) {
            final Long current = counters.get(name);
            counters.put(name, current == null ? 1L : current + 1L);
        }
    }

    private long getOrZero(final String name) {
        final Long value = counters.get(name);
        return value == null ? 0L : value;
    }

    private static String sanitizeOperation(final String operation) {
        return operation == null || operation.isEmpty() ? UNKNOWN_OPERATION : operation;
    }

    private static int nearestRank(final long[] sorted, final int percentile) {
        final int n = sorted.length;
        int rank = (int) Math.ceil(percentile / 100.0d * n);
        if (rank < 1) {
            rank = 1;
        }
        if (rank > n) {
            rank = n;
        }
        final long value = sorted[rank - 1];
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /** 方法键：组件号 × 方法名。<b>带组件号</b>以防不同 RPC 框架方法名撞车。 */
    private static final class RpcKey {

        private final int componentId;
        private final String operation;

        RpcKey(final int componentId, final String operation) {
            this.componentId = componentId;
            this.operation = operation;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RpcKey)) {
                return false;
            }
            final RpcKey other = (RpcKey) o;
            return componentId == other.componentId && operation.equals(other.operation);
        }

        @Override
        public int hashCode() {
            return 31 * componentId + operation.hashCode();
        }
    }

    /** 一条证据样本：耗时 / 时点 / traceId / 是否出错。 */
    private static final class Evidence {

        private final long durationMs;
        private final long startTimeMs;
        private final String traceId;
        private final boolean error;

        Evidence(final long durationMs, final long startTimeMs, final String traceId, final boolean error) {
            this.durationMs = durationMs;
            this.startTimeMs = startTimeMs;
            this.traceId = traceId;
            this.error = error;
        }

        Map<String, Object> toMap(final boolean slowest) {
            final Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("durationMs", durationMs);
            map.put("startTimeMs", startTimeMs);
            map.put("traceId", traceId);
            map.put("isError", error);
            map.put("slowest", slowest);
            return map;
        }
    }

    /**
     * 单个方法的累计单元：精确计数 + 分位蓄水池 + 证据集（最慢 1 + 随机 4）。
     */
    private static final class MethodAccumulator {

        private long requestCount;
        private long errorCount;
        private long totalLatency;
        private long maxLatency;
        /** 首次/最近见到该方法的 span 起始时刻（毫秒）；-1 表示还没见过。 */
        private long firstSeenMs = -1L;
        private long lastSeenMs = -1L;
        /** 见过的总样本数（含没被池子留下的），用于等概率抽样。 */
        private long seen;
        private final long[] samples = new long[MAX_SAMPLES_PER_METHOD];
        private int sampleCount;
        /** 最慢那一条（证据）。 */
        private Evidence slowest;
        /** 随机 4 条（证据，蓄水池）。 */
        private final Evidence[] randomSlots = new Evidence[EVIDENCE_RANDOM_SLOTS];
        private int randomFilled;

        /**
         * 吸收一次观测：计数 + 维护最大 + 首末见，再把耗时投入分位池（蓄水池）与证据集。
         * 蓄水池满后按 {@code pick = floorMod(random.nextLong(), seen)} 挑槽位替换——入池概率随 seen 递减，正是等概率抽样。
         */
        void add(final long duration, final boolean error, final long startTimeMs, final String traceId,
                final Random random) {
            requestCount++;
            if (error) {
                errorCount++;
            }
            totalLatency += duration;
            if (duration > maxLatency) {
                maxLatency = duration;
            }
            if (firstSeenMs < 0L || startTimeMs < firstSeenMs) {
                firstSeenMs = startTimeMs;
            }
            if (startTimeMs > lastSeenMs) {
                lastSeenMs = startTimeMs;
            }
            seen++;
            if (sampleCount < MAX_SAMPLES_PER_METHOD) {
                samples[sampleCount++] = duration;
            } else {
                final long pick = Math.floorMod(random.nextLong(), seen);
                if (pick < MAX_SAMPLES_PER_METHOD) {
                    samples[(int) pick] = duration;
                }
            }
            if (slowest == null || duration > slowest.durationMs) {
                slowest = new Evidence(duration, startTimeMs, traceId, error);
            }
            if (randomFilled < EVIDENCE_RANDOM_SLOTS) {
                randomSlots[randomFilled++] = new Evidence(duration, startTimeMs, traceId, error);
            } else {
                final long pick = Math.floorMod(random.nextLong(), seen);
                if (pick < EVIDENCE_RANDOM_SLOTS) {
                    randomSlots[(int) pick] = new Evidence(duration, startTimeMs, traceId, error);
                }
            }
        }

        int[] percentiles() {
            final int[] out = new int[] { -1, -1, -1 };
            if (sampleCount <= 0) {
                return out;
            }
            final long[] sorted = new long[sampleCount];
            System.arraycopy(samples, 0, sorted, 0, sampleCount);
            Arrays.sort(sorted);
            out[0] = nearestRank(sorted, 50);
            if (sampleCount >= MIN_SAMPLES_FOR_TAILS) {
                out[1] = nearestRank(sorted, 95);
                out[2] = nearestRank(sorted, 99);
            }
            return out;
        }
    }
}
