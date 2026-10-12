package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.List;
import java.util.Map;

import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanLayer;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;
import org.junit.Assert;
import org.junit.Test;

/**
 * RPC 调用排行聚合单测。只测外部行为：喂段 → 读排行榜/证据，断言内容、分位序、有界性与抽样口径。
 * <p>
 * 口径见待办：只算出站 {@code RPCFramework} span；键 = componentId × operationName；进程累计、纯内存。
 * </p>
 */
public class RpcMetricsAggregatorTest {

    private static final String SERVICE = "demo-app";
    private static final long MINUTE_MS = 60_000L;

    /** 实测的组件 id（见 docs/notes/2026-09-30-exit-span-runtime-probe.md）。 */
    private static final int COMPONENT_GRPC = 23;
    private static final int COMPONENT_DUBBO = 11;
    private static final int COMPONENT_H2 = 32;

    private static long now() {
        return System.currentTimeMillis();
    }

    private static SpanObject entry(final String operation, final long start, final long end) {
        return SpanObject.newBuilder().setSpanId(0).setParentSpanId(-1).setOperationName(operation)
                .setStartTime(start).setEndTime(end).setSpanType(SpanType.Entry).build();
    }

    private static SpanObject exit(final String operation, final int componentId, final SpanLayer layer,
            final long start, final long end, final boolean error) {
        return SpanObject.newBuilder().setSpanId(1).setParentSpanId(0).setOperationName(operation)
                .setComponentId(componentId).setSpanLayer(layer).setStartTime(start).setEndTime(end)
                .setSpanType(SpanType.Exit).setIsError(error).build();
    }

    private static SpanObject rpcExit(final String operation, final int componentId, final long start, final long end,
            final boolean error) {
        return exit(operation, componentId, SpanLayer.RPCFramework, start, end, error);
    }

    private static SegmentObject segment(final String traceId, final SpanObject... spans) {
        final SegmentObject.Builder builder = SegmentObject.newBuilder().setTraceId(traceId)
                .setTraceSegmentId("s-" + traceId).setService(SERVICE);
        for (SpanObject span : spans) {
            builder.addSpans(span);
        }
        return builder.build();
    }

    private static RpcMethodRow row(final List<RpcMethodRow> rows, final int componentId, final String operation) {
        for (RpcMethodRow row : rows) {
            if (row.getComponentId() == componentId && operation.equals(row.getOperation())) {
                return row;
            }
        }
        return null;
    }

    private static RpcMetricsAggregator newAggregator() {
        return new RpcMetricsAggregator();
    }

    // ---------------------------------------------------------------- 只收 RPCFramework 出口

    @Test
    public void countsOnlyRpcFrameworkExitSpans() {
        final RpcMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(segment("t1",
                entry("GET:/api/deps-demo/grpc", t, t + 500L),
                rpcExit("demo.DepsDemo/Echo", COMPONENT_GRPC, t + 10L, t + 60L, false),
                exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, SpanLayer.Database, t, t + 5L, false),
                exit("/api/x", 128, SpanLayer.Http, t, t + 5L, false)));

        final List<RpcMethodRow> rows = aggregator.snapshot();
        Assert.assertEquals("只有 RPCFramework 层的出口入榜", 1, rows.size());
        Assert.assertEquals("demo.DepsDemo/Echo", rows.get(0).getOperation());
        Assert.assertEquals("耗时取自出口 span（50ms）", 50L, rows.get(0).getMaxLatency());
    }

    @Test
    public void ignoresSegmentWithoutRpcExit() {
        final RpcMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(segment("t1", entry("GET:/api/order/1", t, t + 100L)));
        Assert.assertTrue("纯入口段不产行", aggregator.snapshot().isEmpty());
    }

    @Test
    public void ignoresNullSegment() {
        final RpcMetricsAggregator aggregator = newAggregator();
        aggregator.onSegment(null);
        Assert.assertTrue(aggregator.snapshot().isEmpty());
    }

    // ---------------------------------------------------------------- 键与计数

    @Test
    public void aggregatesByComponentAndOperation() {
        final RpcMetricsAggregator aggregator = newAggregator();
        final long t = now();
        for (int i = 0; i < 3; i++) {
            aggregator.onSegment(segment("t" + i, rpcExit("demo.DepsDemo/Echo", COMPONENT_GRPC, t, t + 20L, false)));
        }

        final RpcMethodRow row = row(aggregator.snapshot(), COMPONENT_GRPC, "demo.DepsDemo/Echo");
        Assert.assertNotNull(row);
        Assert.assertEquals(3L, row.getRequestCount());
    }

    @Test
    public void splitsByComponentEvenWithSameOperation() {
        final RpcMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(segment("t1", rpcExit("svc/m", COMPONENT_GRPC, t, t + 10L, false)));
        aggregator.onSegment(segment("t2", rpcExit("svc/m", COMPONENT_DUBBO, t, t + 10L, false)));

        Assert.assertEquals("组件号进键，方法名相同也分两行", 2, aggregator.snapshot().size());
    }

    @Test
    public void countsErrorsFromExitSpan() {
        final RpcMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(segment("t1", rpcExit("svc/m", COMPONENT_GRPC, t, t + 10L, true)));
        aggregator.onSegment(segment("t2", rpcExit("svc/m", COMPONENT_GRPC, t, t + 10L, false)));
        aggregator.onSegment(segment("t3", rpcExit("svc/m", COMPONENT_GRPC, t, t + 10L, false)));
        aggregator.onSegment(segment("t4", rpcExit("svc/m", COMPONENT_GRPC, t, t + 10L, false)));

        final RpcMethodRow row = row(aggregator.snapshot(), COMPONENT_GRPC, "svc/m");
        Assert.assertEquals(1L, row.getErrorCount());
        Assert.assertEquals(0.25d, row.errorRate(), 0.0001d);
        Assert.assertEquals("真平均 = 总耗时/次数", 10d, row.avgLatency(), 0.0001d);
    }

    // ---------------------------------------------------------------- 分位

    @Test
    public void computesOrderedPercentiles() {
        final RpcMetricsAggregator aggregator = newAggregator();
        final long t = now();
        for (int i = 1; i <= 200; i++) {
            aggregator.onSegment(segment("t" + i, rpcExit("svc/m", COMPONENT_GRPC, t, t + i, false)));
        }

        final RpcMethodRow row = row(aggregator.snapshot(), COMPONENT_GRPC, "svc/m");
        Assert.assertEquals(200L, row.getRequestCount());
        Assert.assertTrue("分位必须单调 " + row.getP50() + "/" + row.getP95() + "/" + row.getP99() + "/" + row.getMaxLatency(),
                row.getP50() >= 1 && row.getP50() <= row.getP95() && row.getP95() <= row.getP99()
                        && row.getP99() <= row.getMaxLatency());
    }

    @Test
    public void snapshotSortsByP95Desc() {
        final RpcMetricsAggregator aggregator = newAggregator();
        final long t = now();
        // slow: 25 次 100ms；fast: 25 次 5ms —— 都够尾巴样本门槛
        for (int i = 0; i < 25; i++) {
            aggregator.onSegment(segment("slow" + i, rpcExit("svc/slow", COMPONENT_GRPC, t, t + 100L, false)));
            aggregator.onSegment(segment("fast" + i, rpcExit("svc/fast", COMPONENT_GRPC, t, t + 5L, false)));
        }

        final List<RpcMethodRow> rows = aggregator.snapshot();
        Assert.assertEquals("svc/slow", rows.get(0).getOperation());
    }

    // ---------------------------------------------------------------- 有界

    @Test
    public void overflowsIntoOtherWhenMethodLimitReached() {
        final RpcMetricsAggregator aggregator = RpcMetricsAggregator.forTesting(2);
        final long t = now();
        for (int i = 0; i < 5; i++) {
            aggregator.onSegment(segment("t" + i, rpcExit("svc/m" + i, COMPONENT_GRPC, t, t + 10L, false)));
        }

        final List<RpcMethodRow> rows = aggregator.snapshot();
        Assert.assertTrue("方法数有界（含兜底），实际=" + rows.size(), rows.size() <= 3);
        Assert.assertTrue("溢出计数为正",
                ((Number) aggregator.snapshotCounters().get("methodOverflow")).longValue() > 0L);
        Assert.assertNotNull("应有兜底方法行",
                row(rows, RpcMetricsAggregator.OTHER_COMPONENT, RpcMetricsAggregator.OTHER_OPERATION));
    }

    @Test
    public void staysBoundedUnderFlood() {
        final RpcMetricsAggregator aggregator = RpcMetricsAggregator.forTesting(32);
        final long t = now();
        for (int i = 0; i < 2000; i++) {
            aggregator.onSegment(segment("t" + i, rpcExit("svc/m" + i, COMPONENT_GRPC, t, t + 3L, false)));
        }
        Assert.assertTrue("存活方法数有界（含兜底）", aggregator.snapshot().size() <= 33);
    }

    // ---------------------------------------------------------------- 首末见

    @Test
    public void tracksFirstAndLastSeenBucket() {
        final RpcMetricsAggregator aggregator = newAggregator();
        final long first = now() - 5 * MINUTE_MS;
        final long last = now() - 2 * MINUTE_MS;
        aggregator.onSegment(segment("t1", rpcExit("svc/m", COMPONENT_GRPC, first, first + 10L, false)));
        aggregator.onSegment(segment("t2", rpcExit("svc/m", COMPONENT_GRPC, last, last + 10L, false)));

        final RpcMethodRow row = row(aggregator.snapshot(), COMPONENT_GRPC, "svc/m");
        Assert.assertEquals("首见取最早那个分钟桶", first / MINUTE_MS, row.getFirstSeenBucket());
        Assert.assertEquals("末见取最晚那个分钟桶", last / MINUTE_MS, row.getLastSeenBucket());
    }

    @Test
    public void startedAtIsReported() {
        final RpcMetricsAggregator aggregator = newAggregator();
        Assert.assertTrue("进程启动时刻可读（页面显示'自启动以来'的起点）",
                aggregator.getStartedAtMs() > 0L && aggregator.getStartedAtMs() <= now());
    }

    // ---------------------------------------------------------------- 证据

    @Test
    public void evidenceKeepsSlowestPlusRandomUpToFive() {
        final RpcMetricsAggregator aggregator = newAggregator();
        final long t = now();
        for (int i = 0; i < 50; i++) {
            // 第 7 次最慢（999ms），其余 10ms
            final long dur = i == 7 ? 999L : 10L;
            aggregator.onSegment(segment("trace-" + i, rpcExit("svc/m", COMPONENT_GRPC, t, t + dur, false)));
        }

        final List<Map<String, Object>> samples = aggregator.evidence(COMPONENT_GRPC, "svc/m");
        Assert.assertEquals("最慢 1 + 随机 4", 5, samples.size());
        Assert.assertEquals("第一条是最慢那条", Boolean.TRUE, samples.get(0).get("slowest"));
        Assert.assertEquals(999L, ((Number) samples.get(0).get("durationMs")).longValue());
        Assert.assertEquals("traceId 可下钻", "trace-7", samples.get(0).get("traceId"));
        for (int i = 1; i < samples.size(); i++) {
            Assert.assertEquals("随机样本标记为非最慢", Boolean.FALSE, samples.get(i).get("slowest"));
        }
    }

    @Test
    public void evidenceDedupsByTraceId() {
        final RpcMetricsAggregator aggregator = newAggregator();
        final long t = now();
        // 同一 traceId 多次调用：证据按 traceId 去重，只剩最慢那条
        for (int i = 0; i < 10; i++) {
            aggregator.onSegment(segment("same-trace", rpcExit("svc/m", COMPONENT_GRPC, t, t + (i + 1), false)));
        }
        final List<Map<String, Object>> samples = aggregator.evidence(COMPONENT_GRPC, "svc/m");
        Assert.assertEquals(1, samples.size());
    }

    @Test
    public void evidenceEmptyForOtherAndUnknown() {
        final RpcMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(segment("t1", rpcExit("svc/m", COMPONENT_GRPC, t, t + 10L, false)));
        Assert.assertTrue("兜底方法不留证据", aggregator.evidence(RpcMetricsAggregator.OTHER_COMPONENT,
                RpcMetricsAggregator.OTHER_OPERATION).isEmpty());
        Assert.assertTrue("未知方法返回空证据", aggregator.evidence(COMPONENT_GRPC, "svc/nope").isEmpty());
    }

    // ---------------------------------------------------------------- 快照隔离 & 契约

    @Test
    public void snapshotIsDetachedFromInternalState() {
        final RpcMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(segment("t1", rpcExit("svc/m", COMPONENT_GRPC, t, t + 10L, false)));
        final List<RpcMethodRow> first = aggregator.snapshot();
        aggregator.onSegment(segment("t2", rpcExit("svc/m", COMPONENT_GRPC, t, t + 10L, false)));
        Assert.assertEquals("先取的快照不应随后续写入而变化", 1L, first.get(0).getRequestCount());
    }

    @Test
    public void toMapCarriesExpectedFields() {
        final RpcMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(segment("t1", rpcExit("svc/m", COMPONENT_GRPC, t, t + 120L, true)));

        final Map<String, Object> map = aggregator.snapshot().get(0).toMap();
        for (final String key : new String[] { "componentId", "spanLayer", "operation", "requestCount", "errorCount",
                "errorRate", "totalLatency", "avgLatency", "maxLatency", "p50", "p95", "p99", "firstSeenBucket",
                "lastSeenBucket", "sampleCount" }) {
            Assert.assertTrue("读口字段缺失: " + key, map.containsKey(key));
        }
        Assert.assertEquals(RpcMetricsAggregator.RPC_LAYER, map.get("spanLayer"));
    }
}
