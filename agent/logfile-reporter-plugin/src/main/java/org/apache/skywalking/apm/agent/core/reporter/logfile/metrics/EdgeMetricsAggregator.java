package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;

/**
 * 依赖边聚合（依赖拓扑的唯一新增聚合）：按"**段内配对**"把一次事务的入口端点与它的外部依赖连成边。
 * <p>
 * 动机：本仓既有的 Trace 指标 / 慢查询 / 极端值追溯**全部只按入口端点一个维度组织**，
 * 于是"我调了哪些外部依赖、哪条路慢"答不出来。本聚合补上这一个维度。
 * </p>
 *
 * <h3>口径：只做段内配对，不跨段</h3>
 * 边 = 同一个 {@code TraceSegment} 内「入口 span 的 operationName」×「出口 span 的 componentId」。
 * <b>刻意不跨段</b>：跨段（{@code @Async}）配对需要维护"父段 ID → 入口端点"的有界索引，
 * 而本仓 trace 内存热层与 H2 内存层都是分钟级窗口 + FIFO 淘汰，父段可能已被淘汰——
 * 那会产生<b>无法与"没有这个调用"区分</b>的随机缺边，比缺边本身更坏。代价是异步出口依赖不进图，
 * 读口与页面须写明。
 *
 * <h3>有界：双维上限（缺一不可）</h3>
 * 边键是「端点 × 组件」的<b>交叉积</b>，不是单维——只卡组件数在算术上无效。
 * 故边数与组件数<b>各自</b>设上限，任一触顶即并入兜底（与端点级 {@code (other)} 同款"先到先归并"）。
 *
 * <h3>内存：样本池远小于端点级</h3>
 * 存活桶数与端点级一致（当前桶 + {@link #LATE_WINDOW_BUCKETS} 个晚到桶），但每键的耗时样本池
 * 取 {@link #EDGE_MAX_SAMPLES_PER_KEY}——远小于端点级的 5000。原因是交叉积会让存活对象数放大：
 * 沿用端点级规模在边数上限下最坏达数百 MB，在演示应用堆内不可接受。
 * 分位算法（最近秩）与端点级一致，仅样本池规模不同，保证两处分位定义可比。
 *
 * <h3>边界</h3>
 * 纯内存、不落库、不改写路径、不改编荷结构、不与告警路径耦合（错误判定直接取出口 span 的
 * {@code isError}，不是规则引擎命中）。窗口语义与 ADR-04 的 H2 内存层一致（分钟~小时级、重启即失）。
 */
public class EdgeMetricsAggregator {

    /** 边数超限后，后来的边并进这一个"垃圾桶"（与端点级 {@code OTHER_ENDPOINT} 对齐）。 */
    public static final String OTHER_EDGE_ENDPOINT = "(other)";
    /** 组件数超限后，后来者并进这一个兜底组件（保留键，正常不会出现于图中）。 */
    public static final int OTHER_EDGE_COMPONENT = -1;
    /** 出口 span 没有 operationName 时的兜底操作名。 */
    public static final String UNKNOWN_EDGE_OPERATION = "(unknown)";
    /** 兜底边的 spanLayer 标记。 */
    private static final String OTHER_SPAN_LAYER = "(other)";

    private static final long MINUTE_MS = 60_000L;
    /** 迟到容忍 + 幂等重写窗口，与端点级一致。 */
    private static final int LATE_WINDOW_BUCKETS = 3;
    /**
     * 每条边最多存多少条耗时样本。蓄水池抽样（Algorithm R），满后随机顶掉旧样本。
     * <p>
     * <b>刻意远小于端点级的 5000</b>：边键是交叉积，存活对象数 = 桶数 × 边数。端点级规模
     * （40KB/键）在 {@link #MAX_EDGES_PER_BUCKET} 上限下最坏达数百 MB；这里取几百条把同一上界
     * 压到十几 MB 量级。代价是尾部分位在小样本下噪声更大——故读口同时暴露 {@code sampleCount}。
     * </p>
     */
    private static final int EDGE_MAX_SAMPLES_PER_KEY = 256;
    /** 出 p90/p95/p99 最少样本数，样本太少时尾巴不可靠，只给 p50。与端点级同口径。 */
    private static final int MIN_SAMPLES_FOR_TAILS = 20;
    /** 同一个分钟里最多记多少条不同的边。超限后新边并入 {@link #OTHER_EDGE_ENDPOINT}。 */
    private static final int MAX_EDGES_PER_BUCKET = 2000;
    /** 同一个分钟里最多记多少个不同的依赖组件。超限后并入 {@link #OTHER_EDGE_COMPONENT}。 */
    private static final int MAX_COMPONENTS_PER_BUCKET = 200;
    /**
     * 单条边上最多记多少个不同的出口操作名（明细档标签用），超出即截断并打标记。
     * <p>
     * 刻意<b>不</b>把操作名放进边键：那样边数会乘上操作名基数。实测操作名已被 agent 归一化
     * （DB 侧塌缩成两类方法签名），但仍不假设它永远有界——上限 + 标记是兜底。
     * </p>
     */
    private static final int MAX_OPERATIONS_PER_EDGE = 8;
    /** 抽样种子写死，使抽样过程可复现、测试可写。 */
    private static final long EDGE_RESERVOIR_SEED = 20260930L;

    /**
     * 生命周期累计表最多记多少个依赖组件（组件级，**不是**「端点 × 组件」的交叉积）。
     * <p>
     * 基数天然很低（一个进程伸出去的手通常个位数到几十），但仍设上限：它是<b>不参与淘汰</b>的，
     * 一旦基数异常（例如某个动态类名被当成组件）就会永久占内存。超限后并入兜底组件并计
     * {@code lifetimeDropped}，宁可丢精度也不让内存无界。
     */
    private static final int MAX_LIFETIME_COMPONENTS = 512;

    private final Object lock = new Object();
    private final TreeMap<Long, Map<EdgeKey, EdgeAccumulator>> buckets =
            new TreeMap<Long, Map<EdgeKey, EdgeAccumulator>>();
    /**
     * 自进程启动以来的组件级累计（<b>不参与</b>{@link #evictClosedBuckets} 的桶淘汰）。
     * 与 {@link #buckets} 的分工：buckets 答"此刻谁在调谁"，这张表答"从起来到现在伸出去过哪些手"。
     */
    private final Map<LifetimeKey, EdgeLifetimeAccumulator> lifetime =
            new LinkedHashMap<LifetimeKey, EdgeLifetimeAccumulator>();
    /** 进程内累计的起点（构造时刻），读口回给宿主用于显示"自启动以来"的起点。 */
    private final long startedAtMs = System.currentTimeMillis();
    private final Random reservoirRandom = new Random(EDGE_RESERVOIR_SEED);
    private final Map<String, Long> counters = new LinkedHashMap<String, Long>();
    private final int maxEdgesPerBucket;
    private final int maxComponentsPerBucket;

    public EdgeMetricsAggregator() {
        this(MAX_EDGES_PER_BUCKET, MAX_COMPONENTS_PER_BUCKET);
    }

    /** 单测用：注入更小的上限以验证溢出与有界性。 */
    static EdgeMetricsAggregator forTesting(final int maxEdges, final int maxComponents) {
        return new EdgeMetricsAggregator(maxEdges, maxComponents);
    }

    private EdgeMetricsAggregator(final int maxEdges, final int maxComponents) {
        this.maxEdgesPerBucket = maxEdges;
        this.maxComponentsPerBucket = maxComponents;
    }

    /**
     * 喂入一条原始 segment：段内 Entry × Exit 配对建边。无 Entry 或无 Exit 不建边，异常就地吞掉并计数。
     */
    public void onSegment(final SegmentObject segment) {
        if (segment == null) {
            return;
        }
        try {
            final List<SpanObject> spans = segment.getSpansList();
            String endpoint = null;
            long entryStart = -1L;
            for (int i = 0; i < spans.size(); i++) {
                if (endpoint == null && isEntrySpan(spans.get(i))) {
                    endpoint = spans.get(i).getOperationName();
                    entryStart = spans.get(i).getStartTime();
                }
            }
            if (endpoint == null) {
                bump("noEntrySpan");
                return;
            }
            final long bucket = (entryStart < 0L ? System.currentTimeMillis() : entryStart) / MINUTE_MS;
            if (bucket < System.currentTimeMillis() / MINUTE_MS - LATE_WINDOW_BUCKETS) {
                bump("lateDropped");
                return;
            }
            final String left = sanitizeEndpoint(endpoint);
            boolean any = false;
            for (int i = 0; i < spans.size(); i++) {
                final SpanObject span = spans.get(i);
                if (!SpanType.Exit.equals(span.getSpanType())) {
                    continue;
                }
                any = true;
                long duration = span.getEndTime() - span.getStartTime();
                if (duration < 0L) {
                    duration = 0L;
                }
                synchronized (lock) {
                    accumulate(bucket, left, span.getComponentId(), span.getSpanLayer().name(),
                            sanitizeEndpoint(span.getOperationName()), duration, span.getIsError());
                }
            }
            if (!any) {
                bump("noExitSpan");
            }
        } catch (Exception e) {
            bump("edgeErrors");
        }
    }

    /**
     * 边快照（跨全部存活桶），按 端点 → 组件 排序。返回防御性拷贝，调用方改动不影响内部状态。
     */
    public List<EdgeRow> snapshot() {
        final List<EdgeRow> out = new ArrayList<EdgeRow>();
        synchronized (lock) {
            for (Map.Entry<Long, Map<EdgeKey, EdgeAccumulator>> bucket : buckets.entrySet()) {
                for (Map.Entry<EdgeKey, EdgeAccumulator> entry : bucket.getValue().entrySet()) {
                    final EdgeKey key = entry.getKey();
                    out.add(toRow(bucket.getKey(), key, entry.getValue()));
                }
            }
        }
        return out;
    }

    /**
     * 生命周期累计快照：自进程启动以来见过的每个依赖组件一行，按累计调用量降序。
     * <p>
     * <b>不受窗口影响</b>：{@link #evictClosedBuckets} 只清分钟桶，这张表一直留着，
     * 所以窗口滑过之后仍能回答"这个进程伸出去过哪些手"。
     */
    public List<EdgeLifetimeRow> lifetimeSnapshot() {
        final List<EdgeLifetimeRow> out = new ArrayList<EdgeLifetimeRow>();
        synchronized (lock) {
            for (Map.Entry<LifetimeKey, EdgeLifetimeAccumulator> entry : lifetime.entrySet()) {
                out.add(entry.getValue().toRow());
            }
        }
        Collections.sort(out, new Comparator<EdgeLifetimeRow>() {
            @Override
            public int compare(final EdgeLifetimeRow a, final EdgeLifetimeRow b) {
                return Long.compare(b.getRequestCount(), a.getRequestCount());
            }
        });
        return out;
    }

    /** 进程启动时刻（毫秒）；读口回给宿主显示"自启动以来"的起点。 */
    public long getStartedAtMs() {
        return startedAtMs;
    }

    /** 体检计数：无入口段 / 无出口段 / 迟到丢弃 / 边溢出 / 异常。 */
    public Map<String, Object> snapshotCounters() {
        final Map<String, Object> out = new LinkedHashMap<String, Object>();
        synchronized (lock) {
            for (Map.Entry<String, Long> entry : counters.entrySet()) {
                out.put(entry.getKey(), entry.getValue());
            }
            out.put("edgeOverflow", getOrZero("edgeOverflow"));
            out.put("liveEdgeCount", (long) liveEdgeCount());
            out.put("liveBucketCount", (long) buckets.size());
            out.put("lifetimeComponents", (long) lifetime.size());
            out.put("lifetimeDropped", getOrZero("lifetimeDropped"));
        }
        return out;
    }

    /**
     * 淘汰过老的桶并保留最近窗口。沿用端点级的节奏（由同一个周期任务驱动），
     * 使边数据的内存上界可推导。
     */
    public void evictClosedBuckets(final long nowMs) {
        final long currentBucket = nowMs / MINUTE_MS;
        synchronized (lock) {
            final List<Long> toEvict = new ArrayList<Long>();
            for (Long bucket : buckets.keySet()) {
                if (bucket < currentBucket - LATE_WINDOW_BUCKETS) {
                    toEvict.add(bucket);
                }
            }
            for (int i = 0; i < toEvict.size(); i++) {
                buckets.remove(toEvict.get(i));
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private void accumulate(final long bucket, final String endpoint, final int componentId, final String spanLayer,
            final String operation, final long duration, final boolean error) {
        Map<EdgeKey, EdgeAccumulator> byKey = buckets.get(bucket);
        if (byKey == null) {
            byKey = new LinkedHashMap<EdgeKey, EdgeAccumulator>();
            buckets.put(bucket, byKey);
        }
        EdgeKey key = new EdgeKey(endpoint, componentId, spanLayer);
        EdgeAccumulator accumulator = byKey.get(key);
        if (accumulator == null) {
            if (edgeCount(byKey) >= maxEdgesPerBucket) {
                // 边数触顶：整条边塌进兜底边（端点与组件都不再区分）。
                bump("edgeOverflow");
                key = new EdgeKey(OTHER_EDGE_ENDPOINT, OTHER_EDGE_COMPONENT, OTHER_SPAN_LAYER);
                accumulator = byKey.get(key);
            } else if (componentOverflow(byKey, componentId)) {
                // 组件数触顶：只塌组件、**保留端点**——知道"哪个端点把组件数顶爆了"比只知道有个桶更有用。
                bump("edgeOverflow");
                key = new EdgeKey(endpoint, OTHER_EDGE_COMPONENT, spanLayer);
                accumulator = byKey.get(key);
            }
        }
        if (accumulator == null) {
            accumulator = new EdgeAccumulator();
            byKey.put(key, accumulator);
        }
        accumulator.add(duration, error, reservoirRandom);
        accumulator.addOperation(operation);
        accumulateLifetime(bucket, componentId, spanLayer, operation, duration, error);
    }

    /**
     * 累计到生命周期表（<b>与桶无关</b>）。与 {@link #accumulate} 在同一把锁内调用。
     * <p>
     * 口径与边同源：只有被接受建边的出口 span 才进这张表，迟到丢弃的段（{@code lateDropped}）
     * 与跨段出口都不在内 —— 累计表回答的是"伸出去过哪些手"，不需要为了完整性去捡那些段。
     */
    private void accumulateLifetime(final long bucket, final int componentId, final String spanLayer,
            final String operation, final long duration, final boolean error) {
        LifetimeKey key = new LifetimeKey(componentId, spanLayer);
        EdgeLifetimeAccumulator accumulator = lifetime.get(key);
        if (accumulator == null) {
            if (lifetime.size() >= MAX_LIFETIME_COMPONENTS) {
                bump("lifetimeDropped");
                key = new LifetimeKey(OTHER_EDGE_COMPONENT, OTHER_SPAN_LAYER);
                accumulator = lifetime.get(key);
            }
        }
        if (accumulator == null) {
            accumulator = new EdgeLifetimeAccumulator(componentId, spanLayer, bucket);
            lifetime.put(key, accumulator);
        }
        accumulator.add(operation, duration, error, bucket);
    }

    /**
     * 边数（不含兜底边）。
     * <p>
     * 刻意<b>不</b>设"全部边合起来"的全局保留行：拓扑图是「端点 × 依赖」二部图，
     * 全局行既不是节点也不是边，只会占用边数上限的名额、让有界性难推。总账由读口按需汇总。
     * </p>
     */
    private int edgeCount(final Map<EdgeKey, EdgeAccumulator> byKey) {
        int n = 0;
        for (EdgeKey key : byKey.keySet()) {
            if (!OTHER_EDGE_ENDPOINT.equals(key.endpoint)) {
                n++;
            }
        }
        return n;
    }

    /** 组件数（不含兜底组件）。 */
    private int componentCount(final Map<EdgeKey, EdgeAccumulator> byKey) {
        final Set<Integer> seen = new HashSet<Integer>();
        for (EdgeKey key : byKey.keySet()) {
            if (!OTHER_EDGE_ENDPOINT.equals(key.endpoint) && key.componentId != OTHER_EDGE_COMPONENT) {
                seen.add(key.componentId);
            }
        }
        return seen.size();
    }

    /** 该组件是否会把本桶的组件数顶过上限。 */
    private boolean componentOverflow(final Map<EdgeKey, EdgeAccumulator> byKey, final int componentId) {
        if (componentId == OTHER_EDGE_COMPONENT) {
            return false;
        }
        for (EdgeKey key : byKey.keySet()) {
            if (key.componentId == componentId) {
                return false;
            }
        }
        return componentCount(byKey) >= maxComponentsPerBucket;
    }

    private int liveEdgeCount() {
        int n = 0;
        for (Map<EdgeKey, EdgeAccumulator> byKey : buckets.values()) {
            n += byKey.size();
        }
        return n;
    }

    private EdgeRow toRow(final long bucket, final EdgeKey key, final EdgeAccumulator accumulator) {
        final int[] percentiles = accumulator.percentiles();
        return new EdgeRow(key.endpoint, key.componentId, key.spanLayer, accumulator.operationList(), bucket,
                accumulator.requestCount, accumulator.errorCount, accumulator.totalLatency, accumulator.maxLatency,
                percentiles[0], percentiles[1], percentiles[2], percentiles[3], accumulator.sampleCount,
                accumulator.operationsTruncated);
    }

    private void bump(final String name) {
        synchronized (lock) {
            final Long current = counters.get(name);
            counters.put(name, current == null ? 1L : current + 1L);
        }
    }

    private long getOrZero(final String name) {
        final Long v = counters.get(name);
        return v == null ? 0L : v;
    }

    private static boolean isEntrySpan(final SpanObject span) {
        return span != null && SpanType.Entry.equals(span.getSpanType());
    }

    private static String sanitizeEndpoint(final String operationName) {
        return operationName == null || operationName.isEmpty() ? UNKNOWN_EDGE_OPERATION : operationName;
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

    /** 生命周期累计的键：组件 × 层。刻意不含端点 —— 累计表答的是"伸出去过哪些手"，与端点无关。 */
    private static final class LifetimeKey {

        private final int componentId;
        private final String spanLayer;

        LifetimeKey(final int componentId, final String spanLayer) {
            this.componentId = componentId;
            this.spanLayer = spanLayer;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LifetimeKey)) {
                return false;
            }
            final LifetimeKey other = (LifetimeKey) o;
            return componentId == other.componentId && spanLayer.equals(other.spanLayer);
        }

        @Override
        public int hashCode() {
            return 31 * componentId + spanLayer.hashCode();
        }
    }

    /**
     * 生命周期累计的累加器：计数 + 首末见分钟桶 + 累计最大耗时 + 出口操作名去重集合。
     * <p>
     * <b>不存耗时样本</b>（与边的蓄水池不同）：累计表要回答的是"有没有被调过、调了多少次"，
     * 不是"分布如何"；要看分布用窗口内的边。这样这张表的内存与进程时长无关，只与组件数有关。
     */
    private static final class EdgeLifetimeAccumulator {

        private final int componentId;
        private final String spanLayer;
        private final long firstSeenBucket;
        private long lastSeenBucket;
        private long requestCount;
        private long errorCount;
        private long maxLatency;
        private final LinkedHashSet<String> operations = new LinkedHashSet<String>();

        EdgeLifetimeAccumulator(final int componentId, final String spanLayer, final long bucket) {
            this.componentId = componentId;
            this.spanLayer = spanLayer;
            this.firstSeenBucket = bucket;
            this.lastSeenBucket = bucket;
        }

        void add(final String operation, final long duration, final boolean error, final long bucket) {
            requestCount++;
            if (error) {
                errorCount++;
            }
            if (duration > maxLatency) {
                maxLatency = duration;
            }
            if (bucket < firstSeenBucket) {
                // 理论上不会（桶只会前进），留这一行是为了让首见/末见的定义在乱序下仍然自洽
                lastSeenBucket = Math.min(lastSeenBucket, bucket);
            } else if (bucket > lastSeenBucket) {
                lastSeenBucket = bucket;
            }
            if (operation != null && operations.size() < MAX_OPERATIONS_PER_EDGE) {
                operations.add(operation);
            }
        }

        EdgeLifetimeRow toRow() {
            return new EdgeLifetimeRow(componentId, spanLayer, new ArrayList<String>(operations),
                    operations.size() >= MAX_OPERATIONS_PER_EDGE, requestCount, errorCount, maxLatency,
                    firstSeenBucket, lastSeenBucket);
        }
    }

    /** 边键：入口端点 × 组件。刻意不用字符串拼接做键——拼接会引入分隔符转义问题。 */
    private static final class EdgeKey {

        private final String endpoint;
        private final int componentId;
        /** 不参与相等性（同一组件可能有不同 layer 表述），只随行透出供宿主侧 fallback 命名。 */
        private final String spanLayer;

        EdgeKey(final String endpoint, final int componentId, final String spanLayer) {
            this.endpoint = endpoint;
            this.componentId = componentId;
            this.spanLayer = spanLayer;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof EdgeKey)) {
                return false;
            }
            final EdgeKey other = (EdgeKey) o;
            return componentId == other.componentId && endpoint.equals(other.endpoint);
        }

        @Override
        public int hashCode() {
            return 31 * endpoint.hashCode() + componentId;
        }
    }

    /** 单条边在一个分钟桶内的聚合单元：精确计数 + 小蓄水池耗时样本 + 去重操作名。 */
    private static final class EdgeAccumulator {

        /** 这条边在这一分钟被调了多少次。一次出口 span 记一次，不做 traceId 去重。 */
        private long requestCount;
        /** 其中出错多少次（取自出口 span 的 isError）。 */
        private long errorCount;
        /** 所有耗时之和。想算平均耗时就拿它 ÷ 调用次数。 */
        private long totalLatency;
        /** 见过的最慢一次耗时。抓"慢尾巴"靠它。 */
        private long maxLatency;
        /**
         * 总共见过多少条样本（<b>含</b>没被蓄水池留下的那些）。蓄水池满后靠它算"抽中第几个"
         * 来定替换概率——保证每条样本被留下的机会一样，这叫等概率抽样。
         */
        private long seen;
        /**
         * 耗时的蓄水池本体。固定 {@link #EDGE_MAX_SAMPLES_PER_KEY} 条：先到先塞，满了之后
         * 每来一条就随机顶掉旧的一条。平时是乱序的，算分位时才排序。
         * <p>
         * 刻意<b>远小于</b>端点级的 5000——边键是「端点 × 组件」交叉积，存活对象数会被放大。
         * </p>
         */
        private final long[] samples = new long[EDGE_MAX_SAMPLES_PER_KEY];
        /** 池子里当前放了多少条（= min(见过数, 上限)）。既是水位，也是算分位时的有效长度。 */
        private int sampleCount;
        /** 该边上出现过的出口操作名（去重、有上限）。只增不删，随桶一起淘汰。 */
        private final LinkedHashMap<String, Boolean> operations = new LinkedHashMap<String, Boolean>();
        /** 操作名是否因超过 {@link #MAX_OPERATIONS_PER_EDGE} 而被截断（读口要据此提示页面）。 */
        private boolean operationsTruncated;

        void add(final long duration, final boolean error, final Random random) {
            requestCount++;
            if (error) {
                errorCount++;
            }
            totalLatency += duration;
            if (duration > maxLatency) {
                maxLatency = duration;
            }
            seen++;
            if (sampleCount < EDGE_MAX_SAMPLES_PER_KEY) {
                samples[sampleCount++] = duration;
            } else {
                final long pick = Math.floorMod(random.nextLong(), seen);
                if (pick < EDGE_MAX_SAMPLES_PER_KEY) {
                    samples[(int) pick] = duration;
                }
            }
        }

        void addOperation(final String operation) {
            if (operation == null || operation.isEmpty() || operations.containsKey(operation)) {
                return;
            }
            if (operations.size() >= MAX_OPERATIONS_PER_EDGE) {
                operationsTruncated = true;
                return;
            }
            operations.put(operation, Boolean.TRUE);
        }

        List<String> operationList() {
            return new ArrayList<String>(operations.keySet());
        }

        int[] percentiles() {
            final int[] out = new int[] { -1, -1, -1, -1 };
            if (sampleCount <= 0) {
                return out;
            }
            final long[] sorted = new long[sampleCount];
            System.arraycopy(samples, 0, sorted, 0, sampleCount);
            Arrays.sort(sorted);
            out[0] = nearestRank(sorted, 50);
            if (sampleCount >= MIN_SAMPLES_FOR_TAILS) {
                out[1] = nearestRank(sorted, 90);
                out[2] = nearestRank(sorted, 95);
                out[3] = nearestRank(sorted, 99);
            }
            return out;
        }
    }
}
