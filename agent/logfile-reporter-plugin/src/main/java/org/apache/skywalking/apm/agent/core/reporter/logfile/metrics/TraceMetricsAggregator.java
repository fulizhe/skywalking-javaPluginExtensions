package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;

/**
 * trace 指标聚合核心（Phase 5）：按"**入口段即一次事务**"（a1）累计分钟桶。
 * <p>
 * 纯内存、无 I/O；翻转产生的行经 {@link MetricsSink} 交落库（便于单测注入假实现）。
 * 每个桶按 endpoint 与全局汇总（{@link #GLOBAL_ENDPOINT}）各维护一份累加器；
 * 全局行始终按该桶**全样本**计算，不受 endpoint 基数上限影响。
 * </p>
 * <p>
 * 已知偏差（v1）：端点基数超限时按"先到先归并"并入 {@link #OTHER_ENDPOINT}（非 top-N）；
 * 分位数为最近秩精确值，小时 rollup 的近似在后续阶段处理。
 * </p>
 * <p>
 * 追溯（Phase 5 追加）：按 endpoint 记下最极端那一次的 traceId（见 {@link #extremeTraces()}，
 * 本期口径=最大耗时，策略见 {@link ExtremeTraceSelector}），把"指标上的极端值"指回具体链路；
 * 内存记录、不落库，后续 error/slow 明细持久化到 H2 后即形成闭环追踪链条。
 * </p>
 */
public class TraceMetricsAggregator {

    /** "全部接口合起来"这一行的伪接口名——用来做全站总账，不受 500 个接口上限影响，永远单独记账。 */
    public static final String GLOBAL_ENDPOINT = "*";
    /** 接口超过上限后，后来的接口都并进这一个"垃圾桶"里统计。 */
    public static final String OTHER_ENDPOINT = "(other)";
    /** 请求没有 operationName 时的兜底接口名（总得有个地方记账）。 */
    public static final String UNKNOWN_ENDPOINT = "(unknown)";

    /** 一分钟是多少毫秒。把请求开始时间戳除以它，就能算出"这是发生在第几分钟"——得到一个整数编号（分钟桶），统计就按这个编号来。 */
    private static final long MINUTE_MS = 60_000L;
    /**
     * "晚到几天还收不收"的窗口。请求发生的时间比"当前这一分钟"早超过 3 分钟，就认为太旧、统计没意义，直接丢掉（记到
     * counters.lateDropped）。还在内存里的桶分三类：当前这一分钟（还没过完，先不结算）；过去 1~3 分钟（已过完，结算落库，
     * 落下重复也覆盖写，所以结果最终收敛）；3 分钟以前的（下次清理时删掉，腾内存）。
     */
    private static final int LATE_WINDOW_BUCKETS = 3;
    /**
     * 每个"记账本"（Accumulator）最多存多少条耗时样本。前 5000 条原样收下，满了之后每来一条就随机顶掉旧的一条——
     * 这叫"蓄水池抽样"。好处：耗时分布有个代表性样本，算分位数才准；而且内存固定，不管这个接口一秒钟来几千还是几万次，都不再多占。
     */
    private static final int MAX_SAMPLES_PER_KEY = 5000;
    /** 出 p90/p95/p99 这几个"尾巴分位"最少要多少条样本。样本太少时算尾巴不可靠，就只给 p50（中位数），其余返回 -1（前端显示成空）。 */
    private static final int MIN_SAMPLES_FOR_TAILS = 20;
    /**
     * 同一个分钟里，最多记多少个不同的接口（endpoint）。接口太多内存会爆，所以超过 500 个后，新接口的账都记到
     * "(other)" 这一个桶里（"先到先得"，谁先来谁占位置，这是已知的粗略做法）。全局汇总行 "*" 不受此限制，始终单列。
     */
    private static final int MAX_ENDPOINTS_PER_BUCKET = 500;
    /** "最极端那次的 traceId" 也按接口记，最多记 500 个接口（内存有界，跟桶的接口上限一个量级）。 */
    private static final int MAX_EXTREME_ENDPOINTS = 500;
    /** 抽样的随机数"种子"写死。让抽样过程可复现——同一批请求重启后再来一次，抽出来的是同一批样，测试才好写、口径才一致。 */
    private static final long RESERVOIR_SEED = 20260924L;

    /** "算好的账怎么交出去"：翻转算出的行，经它一次性交给存储（落库）。聚合器自己不通数据库，用接口隔开——测试时能换成假的，不会真的去写 H2。 */
    private final MetricsSink sink;
    /** 兜底服务名：一条 segment 没带 service 字段时就用它（一般是 agent 配置里的 agent.service_name，起"总账本"的名）。 */
    private final String defaultService;
    /** "多慢算慢"的阈值（毫秒）。耗时 ≥ 它就记一次 slow（慢请求）。从告警配置读，默认 3000（3 秒）。 */
    private final long defaultSlowThresholdMs;

    /** 一把大锁，统一保护所有内存数据：每来一个请求（写）和大屏查询（读）都要先抢到它，防止读到写到一半的脏数据。简单粗暴，但正确性最好保证。 */
    private final Object lock = new Object();
    /**
     * 数据主仓库：{@code 第几分钟 -> (哪个接口 -> 记账本)}。
     * 用 TreeMap（按键排序的 Map）是为了桶天然按时间排好序——翻转要扫"当前分钟以前"的桶、清理要删"3分钟以前"的桶，顺序都对得上。
     */
    private final TreeMap<Long, Map<String, Accumulator>> buckets = new TreeMap<Long, Map<String, Accumulator>>();
    /** 蓄水池抽样用的随机数发生器：全局共用一个（由固定种子派生），保证同一批数据抽出来一致、可复现。 */
    private final Random reservoirRandom = new Random(RESERVOIR_SEED);
    /** 体检计数：丢了多少、溢出多少、落库出错多少……经 snapshot() 暴露出来，给大屏和调试看。 */
    private final Counters counters = new Counters();

    /**
     * 每个接口记一条"最极端"那次调用的现场（Phase 5 新增：指标上的高峰指回具体一条链路）。
     * {key=接口, 值=该接口当前最夸张那次的 traceId + 现场}。内存记录、不落库。由 {@link #lock} 守护。
     */
    private final Map<String, ExtremeTrace> extremeByEndpoint = new LinkedHashMap<String, ExtremeTrace>();
    /** "界值"怎么算由它说了算：现在是"取最大耗时那条"，留成接口是为了以后能加"超阈值/相对基线"策略，不用动聚合器。 */
    private final ExtremeTraceSelector extremeSelector;
    /**
     * 只读慢阈值解析器（可为 null）：按 operation/url 给出该请求的慢阈值，使指标慢判定复用告警 slow_rules。
     * 解析器须只读、无副作用（不得触发规则命中计数、不得并入告警判定）。
     */
    private final SlowThresholdResolver slowThresholdResolver;

    public TraceMetricsAggregator(final MetricsSink sink, final String defaultService,
            final long defaultSlowThresholdMs) {
        this(sink, defaultService, defaultSlowThresholdMs, new MaxDurationExtremeTraceSelector(), null);
    }

    public TraceMetricsAggregator(final MetricsSink sink, final String defaultService,
            final long defaultSlowThresholdMs, final ExtremeTraceSelector extremeSelector) {
        this(sink, defaultService, defaultSlowThresholdMs, extremeSelector, null);
    }

    public TraceMetricsAggregator(final MetricsSink sink, final String defaultService,
            final long defaultSlowThresholdMs, final SlowThresholdResolver slowThresholdResolver) {
        this(sink, defaultService, defaultSlowThresholdMs, new MaxDurationExtremeTraceSelector(),
                slowThresholdResolver);
    }

    public TraceMetricsAggregator(final MetricsSink sink, final String defaultService,
            final long defaultSlowThresholdMs, final ExtremeTraceSelector extremeSelector,
            final SlowThresholdResolver slowThresholdResolver) {
        this.sink = sink;
        this.defaultService = defaultService;
        this.defaultSlowThresholdMs = defaultSlowThresholdMs;
        this.extremeSelector = extremeSelector != null ? extremeSelector : new MaxDurationExtremeTraceSelector();
        this.slowThresholdResolver = slowThresholdResolver;
    }

    /**
     * 喂入一条原始 segment。非入口段（段内无 Entry span）不计，只记数；异常就地吞掉并计数。
     */
    public void onSegment(final SegmentObject segment) {
        if (segment == null) {
            return;
        }
        try {
            SpanObject entry = null;
            boolean error = false;
            final List<SpanObject> spans = segment.getSpansList();
            for (int i = 0; i < spans.size(); i++) {
                final SpanObject span = spans.get(i);
                // 找 entry 本可遇第一个就停，真正逼着整轮扫的是 error = 段内任意 span isError（:98-100）。现写法一趟两用，该口径下已是最省。
                if (entry == null && isEntrySpan(span)) {
                    entry = span;
                }
                if (span.getIsError()) {
                    error = true;
                }
            }
            if (entry == null) {
                counters.noEntrySpan++;
                return;
            }
            final long startTime = entry.getStartTime();
            final long bucket = startTime / MINUTE_MS;
            final long nowBucket = System.currentTimeMillis() / MINUTE_MS;
            if (bucket < nowBucket - LATE_WINDOW_BUCKETS) {
                counters.lateDropped++;
                return;
            }
            long duration = entry.getEndTime() - startTime;
            if (duration < 0L) {
                duration = 0L;
            }
            final String endpoint = sanitizeEndpoint(entry.getOperationName());
            final String service = sanitizeService(segment.getService());
            final long slowThresholdMs = slowThresholdResolver != null
                    ? slowThresholdResolver.thresholdMs(endpoint, tagValue(entry, "url"), defaultSlowThresholdMs)
                    : defaultSlowThresholdMs;
            final boolean slow = duration >= slowThresholdMs;
            // TODO 锁内每段 2 次 accumulate + 1 次 recordExtreme 共 3 组 map 操作，而同一把锁还被 memoryRows()/snapshot()/flushClosedBuckets 抢——遍历是没变，但每段的锁内成本比上次讨论时高了一档。若日后做极端值追溯，值得考虑它与聚合器是否共锁。
            synchronized (lock) {
                accumulate(bucket, endpoint, duration, error, slow);
                accumulate(bucket, GLOBAL_ENDPOINT, duration, error, slow);
                // 追溯：按 endpoint 记下最极端那一次的 traceId（本期=最大耗时），供读口回溯到具体链路
                recordExtreme(endpoint, service, segment.getTraceId(), duration, error, startTime);
            }
        } catch (Exception e) {
            counters.aggregateErrors++;
        }
    }

    /**
     * 翻转：对保留窗口内已结束的桶算分位、组装行并交落库；窗口外的桶逐出（已在内存中）。可重复调用（幂等覆盖）。
     */
    public void flushClosedBuckets(final long nowMs) {
        final long currentBucket = nowMs / MINUTE_MS;
        final List<MetricsRow> rows = new ArrayList<>();
        synchronized (lock) {
            final List<Long> toEvict = new ArrayList<Long>();
            for (Map.Entry<Long, Map<String, Accumulator>> entry : buckets.entrySet()) {
                final long bucket = entry.getKey();
                if (bucket > currentBucket - 1L) {
                    continue;
                }
                if (bucket < currentBucket - LATE_WINDOW_BUCKETS) {
                    toEvict.add(bucket);
                    continue;
                }
                for (Map.Entry<String, Accumulator> key : entry.getValue().entrySet()) {
                    rows.add(toRow(bucket, key.getKey(), key.getValue()));
                }
            }
            for (int i = 0; i < toEvict.size(); i++) {
                buckets.remove(toEvict.get(i));
            }
        }
        if (sink == null || rows.isEmpty()) {
            return;
        }
        try {
            sink.store(rows);
            synchronized (lock) {
                counters.rowsUpserted += rows.size();
            }
        } catch (Exception e) {
            synchronized (lock) {
                counters.persistErrors++;
            }
        }
    }

    /**
     * 当前内存窗口的全部行（含全局 {@code "*"}），供读口在整分翻转前合并出实时点。
     * 返回新建列表，元素不可变（供只读消费）。
     */
    public List<MetricsRow> memoryRows() {
        final List<MetricsRow> rows = new ArrayList<MetricsRow>();
        synchronized (lock) {
            for (Map.Entry<Long, Map<String, Accumulator>> entry : buckets.entrySet()) {
                for (Map.Entry<String, Accumulator> key : entry.getValue().entrySet()) {
                    rows.add(toRow(entry.getKey(), key.getKey(), key.getValue()));
                }
            }
        }
        return rows;
    }

    /** 当前内存窗口的快照（buckets + counters），JDK 原生类型。 */
    public Map<String, Object> snapshot() {
        final List<Map<String, Object>> bucketRows = new ArrayList<Map<String, Object>>();
        final Map<String, Object> result = new LinkedHashMap<String, Object>();
        synchronized (lock) {
            for (Map.Entry<Long, Map<String, Accumulator>> entry : buckets.entrySet()) {
                for (Map.Entry<String, Accumulator> key : entry.getValue().entrySet()) {
                    bucketRows.add(toRow(entry.getKey(), key.getKey(), key.getValue()).toMap());
                }
            }
            result.put("counters", counters.toMap());
        }
        result.put("buckets", bucketRows);
        result.put("extremeSelector", extremeSelector.name());
        result.put("extremes", extremeTraces());
        return result;
    }

    /**
     * 记录端点极端值对应的 trace（须在 {@link #lock} 内调用）。
     * <p>全局伪端点 {@link #GLOBAL_ENDPOINT} 不单独记——它的极端来自某个具体 endpoint，避免语义混淆。
     * 端点基数达 {@link #MAX_EXTREME_ENDPOINTS} 后不再接纳新端点（内存有界；已知偏差同桶端点上限）。</p>
     */
    private void recordExtreme(final String endpoint, final String service, final String traceId, final long duration,
            final boolean error, final long startTime) {
        if (GLOBAL_ENDPOINT.equals(endpoint)) {
            return;
        }
        final ExtremeTrace current = extremeByEndpoint.get(endpoint);
        if (!extremeSelector.shouldReplace(current, duration, error)) {
            return;
        }
        if (current == null && extremeByEndpoint.size() >= MAX_EXTREME_ENDPOINTS) {
            return;
        }
        extremeByEndpoint.put(endpoint, new ExtremeTrace(endpoint, service, traceId, duration, error, startTime));
    }

    /**
     * 每端点极端值对应的 trace 记录（按耗时降序），供读口回溯到具体链路。
     * <p>本期口径见 {@link #getExtremeSelectorName()}；进程存活期内有效（不落库），读口返回 JDK 原生 Map。</p>
     */
    public List<Map<String, Object>> extremeTraces() {
        final List<ExtremeTrace> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<ExtremeTrace>(extremeByEndpoint.values());
        }
        Collections.sort(snapshot, new Comparator<ExtremeTrace>() {
            @Override
            public int compare(final ExtremeTrace a, final ExtremeTrace b) {
                return Long.compare(b.getDurationMs(), a.getDurationMs());
            }
        });
        final List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(snapshot.size());
        for (ExtremeTrace trace : snapshot) {
            out.add(trace.toMap());
        }
        return out;
    }

    /** 当前极端值判定策略名（预留可配置阈值的标注位，如 {@code max-duration}）。 */
    public String getExtremeSelectorName() {
        return extremeSelector.name();
    }

    private void accumulate(final long bucket, final String endpoint, final long duration,
            final boolean error, final boolean slow) {
        Map<String, Accumulator> byKey = buckets.get(bucket);
        if (byKey == null) {
            byKey = new LinkedHashMap<String, Accumulator>();
            buckets.put(bucket, byKey);
        }
        String key = endpoint;
        Accumulator accumulator = byKey.get(key);
        if (accumulator == null && !GLOBAL_ENDPOINT.equals(key)
                && endpointCount(byKey) >= MAX_ENDPOINTS_PER_BUCKET) {
            counters.endpointOverflow++;
            key = OTHER_ENDPOINT;
            accumulator = byKey.get(key);
        }
        if (accumulator == null) {
            accumulator = new Accumulator();
            byKey.put(key, accumulator);
        }
        if (accumulator.isSampleSaturated()) {
            counters.sampleOverflow++;
        }
        accumulator.add(duration, error, slow, reservoirRandom);
    }

    /** 端点基数（不含全局保留键 {@code "*"}）：上限只约束真实 endpoint。 */
    private static int endpointCount(final Map<String, Accumulator> byKey) {
        return byKey.size() - (byKey.containsKey(GLOBAL_ENDPOINT) ? 1 : 0);
    }

    private MetricsRow toRow(final long bucket, final String endpoint, final Accumulator accumulator) {
        final int[] percentiles = accumulator.percentiles();
        return new MetricsRow(endpoint, bucket, accumulator.requestCount, accumulator.errorCount,
                accumulator.slowCount, accumulator.totalLatency, accumulator.maxLatency, percentiles[0], percentiles[1],
                percentiles[2], percentiles[3], accumulator.sampleCount);
    }

    private static boolean isEntrySpan(final SpanObject span) {
        // a1：只认 Entry span（Entry 即根 span）。不能放宽到"parentSpanId==-1"的任意根 span，
        // 否则会把无上下文自成一段的 DB/pool 操作（根为 Local/Exit）与跨线程异步子段（根为 Local）误计为事务。
        return span != null && SpanType.Entry.equals(span.getSpanType());
    }

    /** 从入口 span 的 tag 中取 url（slow_rules 的 {@code url:} 规则用）；无则返回 null。 */
    private static String tagValue(final SpanObject span, final String key) {
        if (span == null || key == null) {
            return null;
        }
        final List<KeyStringValuePair> tags = span.getTagsList();
        for (int i = 0; i < tags.size(); i++) {
            if (key.equals(tags.get(i).getKey())) {
                return tags.get(i).getValue();
            }
        }
        return null;
    }

    private static String sanitizeEndpoint(final String operationName) {
        return operationName == null || operationName.isEmpty() ? UNKNOWN_ENDPOINT : operationName;
    }

    private String sanitizeService(final String service) {
        return service == null || service.isEmpty() ? defaultService : service;
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

    /**
     * 单个 (endpoint) 在某个分钟桶内的聚合单元：
     * 精确计数（请求/错误/慢/总耗时/最大） + 蓄水池时长样本（只增，不因翻转清空；翻转时由 {@link #percentiles} 现算分位）。
     * "先到先得"的桶内端点之一，全局保留键 {@link "*"} 各桶各持一份，全样本统计。
     */
    private static final class Accumulator {

        /** 这个接口在这一分钟总共来了多少次请求。每次都算，不做 traceId 去重，正常请求也计入。 */
        private long requestCount;
        /** 其中出错多少次：一次请求里任何一个 span 报过错，就记成"错了一次"。 */
        private long errorCount;
        /** 其中"够慢"多少次：耗时达到 {@link #defaultSlowThresholdMs} 阈值就 +1。 */
        private long slowCount;
        /** 所有耗时的总和。想算平均耗时就拿它 ÷ 请求数（avgLatency）。 */
        private long totalLatency;
        /** 这一分钟内见过的最慢一次耗时（0 表示还没见过任何请求，第一条就会覆盖它）。抓"慢尾巴"靠它。 */
        private long maxLatency;
        /** 总共见过多少条样本。蓄水池满后，抽数是靠它算"第几个"来定替换概率——保证每一条样本被留下的机会一样，这叫等概率抽样。 */
        private long seen;
        /** 蓄水池本体：一个固定大小的数组存耗时。前 5000 条按顺序塞，满了之后随机顶掉旧的一条。平时是乱序的，只有算分位时才排序。 */
        private final long[] samples = new long[MAX_SAMPLES_PER_KEY];
        /** 池子里现在放了多少条样本（= 见过数取 5000 封顶）。既表示"水池水位"，又是算分位时要排序的有效长度。 */
        private int sampleCount;

        /**
         * 吸收一次观测：各项计数自增、维护最大，再把时长投入蓄水池。
         * {@code random} 由聚合器共享（单一随机源保证整体序列一致）；蓄水池满后按
         * {@code pick = floorMod(random.nextLong(), seen)} 挑一个池中槽位替换——入池概率随 seen 递减，正是等概率抽样。
         */
        void add(final long duration, final boolean error, final boolean slow, final Random random) {
            requestCount++;
            if (error) {
                errorCount++;
            }
            if (slow) {
                slowCount++;
            }
            totalLatency += duration;
            if (duration > maxLatency) {
                maxLatency = duration;
            }
            seen++;
            if (sampleCount < MAX_SAMPLES_PER_KEY) {
                samples[sampleCount++] = duration;
            } else {
                final long pick = Math.floorMod(random.nextLong(), seen);
                if (pick < MAX_SAMPLES_PER_KEY) {
                    samples[(int) pick] = duration;
                }
            }
        }

        /** 池是否已满（供外部计数 sampleOverflow，仅统计、不影响行为）。 */
        boolean isSampleSaturated() {
            return sampleCount >= MAX_SAMPLES_PER_KEY;
        }

        /**
         * 现算分位数：对当前池内样本复制+排序，nearest-rank 取位。
         * 样本 < {@link #MIN_SAMPLES_FOR_TAILS} 时 p90/95/99 置 -1（读口转 null）；p50 恒可用。
         * 池满后这是一个近似分布（抽样），未满时对窗口内已见样本精确。
         */
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

    /** 聚合器运行计数（全部 long、只增；经 {@link #snapshot()} 的 {@code counters} 暴露，纯净、无逻辑副作用）。 */
    private static final class Counters {

        /** 迟到被丢的数量：请求发生时间早于「当前分钟 - 3」就被视为太旧，统计没意义，丢掉。 */
        private long lateDropped;
        /** 不带"入口 span"的 segment 数：这不算一次事务（可能是无头自建的底层调用），直接跳过不统计。 */
        private long noEntrySpan;
        /** 蓄水池已满时又进来的样本数（满仓后开始随机顶替，这是抽样正常在工作，不是出错）。 */
        private long sampleOverflow;
        /** 接口超过 500 个后，新接口被塞进 (other) 的次数。 */
        private long endpointOverflow;
        /** 落库（sink.store）抛异常的次数——真出错时看它。 */
        private long persistErrors;
        /** 成功写进存储的行数累计（可用来判断翻转有没有在干活）。 */
        private long rowsUpserted;
        /** onSegment 处理请求时自己抛异常的次数（正常情况下应该是 0，非 0 就是有 bug 的信号）。 */
        private long aggregateErrors;

        Map<String, Object> toMap() {
            final Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("lateDropped", lateDropped);
            map.put("noEntrySpan", noEntrySpan);
            map.put("sampleOverflow", sampleOverflow);
            map.put("endpointOverflow", endpointOverflow);
            map.put("persistErrors", persistErrors);
            map.put("rowsUpserted", rowsUpserted);
            map.put("aggregateErrors", aggregateErrors);
            return map;
        }
    }
}
