package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

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
 */
public class TraceMetricsAggregator {

    /** 全局汇总的保留 endpoint 键。 */
    public static final String GLOBAL_ENDPOINT = "*";
    /** 端点基数溢出归并键。 */
    public static final String OTHER_ENDPOINT = "(other)";
    /** 无 operationName 的兜底端点。 */
    public static final String UNKNOWN_ENDPOINT = "(unknown)";

    private static final long MINUTE_MS = 60_000L;
    private static final int LATE_WINDOW_BUCKETS = 3;
    private static final int MAX_SAMPLES_PER_KEY = 5000;
    private static final int MIN_SAMPLES_FOR_TAILS = 20;
    private static final int MAX_ENDPOINTS_PER_BUCKET = 500;
    private static final long RESERVOIR_SEED = 20260924L;

    private final MetricsSink sink;
    private final String defaultService;
    private final long defaultSlowThresholdMs;

    private final Object lock = new Object();
    private final TreeMap<Long, Map<String, Accumulator>> buckets = new TreeMap<Long, Map<String, Accumulator>>();
    private final Random reservoirRandom = new Random(RESERVOIR_SEED);
    private final Counters counters = new Counters();

    public TraceMetricsAggregator(final MetricsSink sink, final String defaultService,
            final long defaultSlowThresholdMs) {
        this.sink = sink;
        this.defaultService = defaultService;
        this.defaultSlowThresholdMs = defaultSlowThresholdMs;
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
            final boolean slow = duration >= defaultSlowThresholdMs;
            synchronized (lock) {
                accumulate(bucket, endpoint, service, duration, error, slow);
                accumulate(bucket, GLOBAL_ENDPOINT, service, duration, error, slow);
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
        final List<MetricsRow> rows = new ArrayList<MetricsRow>();
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
        return result;
    }

    private void accumulate(final long bucket, final String endpoint, final String service, final long duration,
            final boolean error, final boolean slow) {
        Map<String, Accumulator> byKey = buckets.get(bucket);
        if (byKey == null) {
            byKey = new LinkedHashMap<String, Accumulator>();
            buckets.put(bucket, byKey);
        }
        String key = endpoint;
        Accumulator accumulator = byKey.get(key);
        if (accumulator == null && !GLOBAL_ENDPOINT.equals(key) && byKey.size() >= MAX_ENDPOINTS_PER_BUCKET) {
            counters.endpointOverflow++;
            key = OTHER_ENDPOINT;
            accumulator = byKey.get(key);
        }
        if (accumulator == null) {
            accumulator = new Accumulator(service);
            byKey.put(key, accumulator);
        }
        if (accumulator.isSampleSaturated()) {
            counters.sampleOverflow++;
        }
        accumulator.add(duration, error, slow, reservoirRandom);
    }

    private MetricsRow toRow(final long bucket, final String endpoint, final Accumulator accumulator) {
        final int[] percentiles = accumulator.percentiles();
        return new MetricsRow(accumulator.service, endpoint, bucket, accumulator.requestCount, accumulator.errorCount,
                accumulator.slowCount, accumulator.totalLatency, accumulator.maxLatency, percentiles[0], percentiles[1],
                percentiles[2], percentiles[3], accumulator.sampleCount);
    }

    private static boolean isEntrySpan(final SpanObject span) {
        return span != null && (SpanType.Entry.equals(span.getSpanType()) || span.getParentSpanId() == -1);
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

    private static final class Accumulator {

        private final String service;
        private long requestCount;
        private long errorCount;
        private long slowCount;
        private long totalLatency;
        private long maxLatency;
        private long seen;
        private final long[] samples = new long[MAX_SAMPLES_PER_KEY];
        private int sampleCount;

        Accumulator(final String service) {
            this.service = service;
        }

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

        boolean isSampleSaturated() {
            return sampleCount >= MAX_SAMPLES_PER_KEY;
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

    private static final class Counters {

        private long lateDropped;
        private long noEntrySpan;
        private long sampleOverflow;
        private long endpointOverflow;
        private long persistErrors;
        private long rowsUpserted;
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
