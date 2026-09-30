package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.List;
import java.util.Map;

import org.apache.skywalking.apm.network.language.agent.v3.RefType;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentReference;
import org.apache.skywalking.apm.network.language.agent.v3.SpanLayer;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;
import org.junit.Assert;
import org.junit.Test;

/**
 * 依赖边聚合（依赖拓扑）单测。只测外部行为：喂段 → 读边快照，断言边内容与有界性。
 * <p>
 * 口径见 spec：<b>只做段内配对</b>（同段 Entry × Exit），不跨段。
 * </p>
 */
public class EdgeMetricsAggregatorTest {

    private static final String SERVICE = "demo-app";
    private static final long SLOW_THRESHOLD_MS = 3000L;
    private static final long MINUTE_MS = 60_000L;

    /** 实测的 componentId（见 docs/notes/2026-09-30-exit-span-runtime-probe.md）。 */
    private static final int COMPONENT_H2 = 32;
    private static final int COMPONENT_HTTPCLIENT = 2;
    private static final int COMPONENT_HUTOOL = 128;

    private static long now() {
        return System.currentTimeMillis();
    }

    private static SpanObject entry(final String operation, final long start, final long end) {
        return SpanObject.newBuilder().setSpanId(0).setParentSpanId(-1).setOperationName(operation)
                .setStartTime(start).setEndTime(end).setSpanType(SpanType.Entry).build();
    }

    private static SpanObject exit(final String operation, final int componentId, final long start, final long end,
            final boolean error) {
        return exit(operation, componentId, SpanLayer.Database, start, end, error);
    }

    private static SpanObject exit(final String operation, final int componentId, final SpanLayer layer,
            final long start, final long end, final boolean error) {
        return SpanObject.newBuilder().setSpanId(1).setParentSpanId(0).setOperationName(operation)
                .setComponentId(componentId).setSpanLayer(layer).setStartTime(start).setEndTime(end)
                .setSpanType(SpanType.Exit).setIsError(error).build();
    }

    /** 一个含 Entry + 若干 Exit 的段——段内配对的正常形态。 */
    private static SegmentObject segmentWithExits(final String endpoint, final SpanObject... exits) {
        final SegmentObject.Builder builder = SegmentObject.newBuilder().setTraceId("t").setTraceSegmentId("s")
                .setService(SERVICE);
        final long base = now();
        builder.addSpans(entry(endpoint, base, base + 500L));
        for (SpanObject e : exits) {
            builder.addSpans(e);
        }
        return builder.build();
    }

    private static EdgeMetricsAggregator newAggregator() {
        return new EdgeMetricsAggregator();
    }

    private static EdgeRow edge(final List<EdgeRow> rows, final String endpoint, final int componentId) {
        for (EdgeRow row : rows) {
            if (endpoint.equals(row.getEndpoint()) && componentId == row.getComponentId()) {
                return row;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 段内配对

    @Test
    public void pairsEntryWithExitWithinSameSegment() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(SegmentObject.newBuilder().setTraceId("t").setTraceSegmentId("s").setService(SERVICE)
                .addSpans(entry("GET:/api/order/1", t, t + 900L))
                .addSpans(exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, t + 10L, t + 310L, false))
                .build());

        final List<EdgeRow> rows = aggregator.snapshot();
        Assert.assertEquals("应产生一条依赖边", 1, rows.size());

        final EdgeRow row = rows.get(0);
        Assert.assertEquals("GET:/api/order/1", row.getEndpoint());
        Assert.assertEquals(COMPONENT_H2, row.getComponentId());
        Assert.assertEquals(1L, row.getRequestCount());
        Assert.assertEquals(0L, row.getErrorCount());
        Assert.assertEquals("耗时取自出口 span（300ms），而非入口段（900ms）", 300L, row.getMaxLatency());
    }

    @Test
    public void aggregatesRepeatedCallsIntoSameEdge() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        for (int i = 0; i < 3; i++) {
            aggregator.onSegment(segmentWithExits("GET:/api/order/1",
                    exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, t, t + 100L, false)));
        }

        final EdgeRow row = edge(aggregator.snapshot(), "GET:/api/order/1", COMPONENT_H2);
        Assert.assertNotNull(row);
        Assert.assertEquals(3L, row.getRequestCount());
    }

    @Test
    public void keepsErrorCountFromExitSpanOnly() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(segmentWithExits("GET:/api/order/1",
                exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, t, t + 100L, true),
                exit("HttpClient/GET", COMPONENT_HTTPCLIENT, t, t + 50L, false)));

        Assert.assertEquals(1L, edge(aggregator.snapshot(), "GET:/api/order/1", COMPONENT_H2).getErrorCount());
        Assert.assertEquals(0L, edge(aggregator.snapshot(), "GET:/api/order/1", COMPONENT_HTTPCLIENT).getErrorCount());
    }

    @Test
    public void splitsEdgesPerComponent() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(segmentWithExits("GET:/api/order/1",
                exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, t, t + 100L, false),
                exit("HttpClient/GET", COMPONENT_HTTPCLIENT, t, t + 50L, false),
                exit("HutoolHttp/post", COMPONENT_HUTOOL, t, t + 20L, false)));

        Assert.assertEquals("三个组件 → 三条边", 3, aggregator.snapshot().size());
    }

    // ---------------------------------------------------------------- 负向：核心取舍

    /**
     * <b>核心取舍的钉子</b>：异步（跨段）出口依赖<b>不进图</b>。
     * <p>
     * 构造的是**真正的跨段形态**：异步子段的段内**没有 Entry span**（根是 Local），
     * 但它**带父段引用**（{@code SegmentReference}）——所以它<b>不是</b>孤段，
     * {@code isOrphanSegment} 会放它进链路存储，也确实会到达本聚合器。
     * 这与「无引用无 Entry」的孤段是<b>两种不同形态</b>，各自的测试分开钉。
     * </p>
     * <p>
     * 不建边的理由：跨段配对需要维护「父段 ID → 入口端点」的有界索引，而本仓 trace
     * 内存热层与 H2 内存层都是分钟级窗口 + FIFO 淘汰，父段可能已被淘汰——
     * 那会产生<b>无法与"没有这个调用"区分</b>的随机缺边，比缺边本身更坏。
     * </p>
     * <p>
     * 此测试存在的意义是防止后人"顺手修好"它而破坏已知边界。<b>改动前请先读这段注释。</b>
     * </p>
     */
    @Test
    public void doesNotCreateEdgeForAsyncCrossSegmentExit() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        // 异步子段:根 span 是 Local 且带父段引用(指向父段的 Entry),段内另有 Exit。
        final SegmentObject asyncChild = SegmentObject.newBuilder().setTraceId("t-child").setTraceSegmentId("child")
                .setService(SERVICE)
                .addSpans(SpanObject.newBuilder().setSpanId(0).setParentSpanId(-1).setOperationName("async work")
                        .setStartTime(t).setEndTime(t + 50L).setSpanType(SpanType.Local)
                        .addRefs(SegmentReference.newBuilder().setRefType(RefType.CrossThread)
                                .setTraceId("t-parent").setParentTraceSegmentId("parent-seg").setParentSpanId(0)
                                .setParentService(SERVICE).setParentEndpoint("GET:/api/order/1")
                                .setParentServiceInstance(SERVICE + "-i1").build())
                        .build())
                .addSpans(exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, SpanLayer.Database, t, t + 100L,
                        false))
                .build();

        aggregator.onSegment(asyncChild);

        Assert.assertTrue("带父段引用的异步子段不应产生依赖边", aggregator.snapshot().isEmpty());
        Assert.assertEquals("应计入无入口段计数(有引用,但段内无 Entry)",
                1L, ((Number) aggregator.snapshotCounters().get("noEntrySpan")).longValue());
    }

    @Test
    public void doesNotCreateEdgeForOrphanSegmentWithoutEntryOrRef() {
        // 与上面的跨段形态对照:无引用且无 Entry = 孤段,更不该建边。
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(SegmentObject.newBuilder().setTraceId("t").setTraceSegmentId("s").setService(SERVICE)
                .addSpans(exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, SpanLayer.Database, t, t + 100L,
                        false))
                .build());

        Assert.assertTrue(aggregator.snapshot().isEmpty());
    }

    @Test
    public void doesNotCreateEdgeForSegmentWithoutEntrySpan() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(SegmentObject.newBuilder().setTraceId("t").setTraceSegmentId("s").setService(SERVICE)
                .addSpans(SpanObject.newBuilder().setSpanId(0).setParentSpanId(-1).setOperationName("Local work")
                        .setStartTime(t).setEndTime(t + 10L).setSpanType(SpanType.Local).build())
                .addSpans(exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, t, t + 100L, false))
                .build());

        Assert.assertTrue("无 Entry span 的段不建边", aggregator.snapshot().isEmpty());
    }

    @Test
    public void ignoresSegmentWithoutExitSpan() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(SegmentObject.newBuilder().setTraceId("t").setTraceSegmentId("s").setService(SERVICE)
                .addSpans(entry("GET:/api/order/1", t, t + 100L)).build());

        Assert.assertTrue("纯入口段（无出口）不产生边", aggregator.snapshot().isEmpty());
    }

    @Test
    public void ignoresNullSegment() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        aggregator.onSegment(null);
        Assert.assertTrue(aggregator.snapshot().isEmpty());
    }

    // ---------------------------------------------------------------- 分位

    @Test
    public void computesOrderedPercentiles() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        // 1..200ms 各一次，凑够尾部样本门槛。
        for (int i = 1; i <= 200; i++) {
            aggregator.onSegment(segmentWithExits("GET:/api/order/1",
                    exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, t, t + i, false)));
        }

        final EdgeRow row = edge(aggregator.snapshot(), "GET:/api/order/1", COMPONENT_H2);
        Assert.assertNotNull(row);
        Assert.assertEquals(200L, row.getRequestCount());
        Assert.assertTrue("p50 应落在样本区间内", row.getP50() >= 1 && row.getP50() <= 200);
        Assert.assertTrue("分位必须单调 " + row.getP50() + "/" + row.getP90() + "/" + row.getP95() + "/" + row.getP99()
                + "/" + row.getMaxLatency(),
                row.getP50() <= row.getP90() && row.getP90() <= row.getP95() && row.getP95() <= row.getP99()
                        && row.getP99() <= row.getMaxLatency());
    }

    // ---------------------------------------------------------------- 有界：双维上限

    @Test
    public void overflowsIntoOtherEdgeWhenEdgeLimitReached() {
        final EdgeMetricsAggregator aggregator = EdgeMetricsAggregator.forTesting(2, 1000);
        final long t = now();
        for (int i = 0; i < 5; i++) {
            aggregator.onSegment(segmentWithExits("GET:/ep" + i,
                    exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, t, t + 10L, false)));
        }

        final List<EdgeRow> rows = aggregator.snapshot();
        Assert.assertTrue("边数应被上限约束（含兜底边），实际=" + rows.size(), rows.size() <= 3);
        Assert.assertTrue("溢出计数应为正",
                ((Number) aggregator.snapshotCounters().get("edgeOverflow")).longValue() > 0L);
        Assert.assertNotNull("应有兜底边（端点与组件都不再区分）",
                edge(rows, EdgeMetricsAggregator.OTHER_EDGE_ENDPOINT, EdgeMetricsAggregator.OTHER_EDGE_COMPONENT));
    }

    @Test
    public void overflowsIntoOtherComponentWhenComponentLimitReached() {
        // 边上限很宽、组件上限很窄：同一端点连 4 个不同组件。
        final EdgeMetricsAggregator aggregator = EdgeMetricsAggregator.forTesting(1000, 2);
        final long t = now();
        aggregator.onSegment(segmentWithExits("GET:/api/order/1",
                exit("c1", 1, t, t + 10L, false),
                exit("c2", 2, t, t + 10L, false),
                exit("c3", 3, t, t + 10L, false),
                exit("c4", 4, t, t + 10L, false)));

        Assert.assertTrue("溢出计数应为正",
                ((Number) aggregator.snapshotCounters().get("edgeOverflow")).longValue() > 0L);
        Assert.assertNotNull("组件超限后应塌到兜底组件，但**保留端点**",
                edge(aggregator.snapshot(), "GET:/api/order/1", EdgeMetricsAggregator.OTHER_EDGE_COMPONENT));
        Assert.assertNotNull("前两个组件应各自独立成边",
                edge(aggregator.snapshot(), "GET:/api/order/1", 1));
    }

    @Test
    public void staysBoundedUnderEdgeFlood() {
        final EdgeMetricsAggregator aggregator = EdgeMetricsAggregator.forTesting(64, 8);
        final long t = now();
        // 远超上限：2000 个端点 × 12 个组件 = 24000 次调用
        for (int e = 0; e < 2000; e++) {
            for (int c = 0; c < 12; c++) {
                aggregator.onSegment(segmentWithExits("GET:/ep" + e, exit("op" + c, c, t, t + 10L, false)));
            }
        }

        final List<EdgeRow> rows = aggregator.snapshot();
        Assert.assertTrue("存活边数=" + rows.size() + " 必须有界", rows.size() <= 64 + 8);
        Assert.assertTrue("溢出计数应显著为正",
                ((Number) aggregator.snapshotCounters().get("edgeOverflow")).longValue() > 0L);
    }

    // ---------------------------------------------------------------- 快照隔离

    @Test
    public void snapshotIsDetachedFromInternalState() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(segmentWithExits("GET:/api/order/1",
                exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, t, t + 100L, false)));

        final List<EdgeRow> first = aggregator.snapshot();
        Assert.assertEquals(1, first.size());
        aggregator.onSegment(segmentWithExits("GET:/api/order/1",
                exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, t, t + 100L, false)));
        Assert.assertEquals("先取的快照不应随后续写入而变化", 1L, first.get(0).getRequestCount());
    }

    @Test
    public void toMapCarriesFourMetricsPlusIdentity() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(segmentWithExits("GET:/api/order/1",
                exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, t, t + 120L, true)));

        final Map<String, Object> map = aggregator.snapshot().get(0).toMap();
        for (final String key : new String[] {"endpoint", "componentId", "spanLayer", "timeBucket", "requestCount",
                "errorCount", "p50", "p90", "p95", "p99", "maxLatency"}) {
            Assert.assertTrue("读口字段缺失: " + key, map.containsKey(key));
        }
        Assert.assertEquals("GET:/api/order/1", map.get("endpoint"));
        Assert.assertEquals(COMPONENT_H2, ((Number) map.get("componentId")).intValue());
    }

    /**
     * spanLayer 必须随边透出：宿主侧的组件名 fallback 依赖它
     * （实测 hutool-http 的 componentId=128 不在组件库里，见 docs/notes/2026-09-30-exit-span-runtime-probe.md）。
     */
    @Test
    public void carriesSpanLayerForHostSideNameFallback() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(segmentWithExits("GET:/api/order/1",
                exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, SpanLayer.Database, t, t + 100L, false)));
        aggregator.onSegment(segmentWithExits("GET:/api/hutool-demo/post-json",
                exit("/api/hutool-demo/echo/json", COMPONENT_HUTOOL, SpanLayer.Http, t, t + 100L, false)));

        Assert.assertEquals("Database", edge(aggregator.snapshot(), "GET:/api/order/1", COMPONENT_H2).getSpanLayer());
        Assert.assertEquals("Http", edge(aggregator.snapshot(), "GET:/api/hutool-demo/post-json", COMPONENT_HUTOOL)
                .getSpanLayer());
    }

    @Test
    public void errorRateIsExposedForHostSideShading() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        aggregator.onSegment(segmentWithExits("GET:/a", exit("c", COMPONENT_H2, t, t + 10L, true)));
        aggregator.onSegment(segmentWithExits("GET:/a", exit("c", COMPONENT_H2, t, t + 10L, false)));
        aggregator.onSegment(segmentWithExits("GET:/a", exit("c", COMPONENT_H2, t, t + 10L, false)));
        aggregator.onSegment(segmentWithExits("GET:/a", exit("c", COMPONENT_H2, t, t + 10L, false)));

        Assert.assertEquals(0.25d, edge(aggregator.snapshot(), "GET:/a", COMPONENT_H2).errorRate(), 0.0001d);
    }

    // ---------------------------------------------------------------- 出口操作名（明细档标签）

    @Test
    public void collectsDistinctExitOperationsPerEdge() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        // 同一端点、同一组件，两个不同操作名 + 一个重复
        aggregator.onSegment(segmentWithExits("GET:/a",
                exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, t, t + 10L, false),
                exit("H2/JDBC/PreparedStatement/execute", COMPONENT_H2, t, t + 10L, false)));
        aggregator.onSegment(segmentWithExits("GET:/a",
                exit("H2/JDBC/PreparedStatement/executeQuery", COMPONENT_H2, t, t + 10L, false)));

        final List<EdgeRow> rows = aggregator.snapshot();
        Assert.assertEquals("操作名不进边键，故仍是同一条边", 1, rows.size());
        Assert.assertEquals(3L, rows.get(0).getRequestCount());
        Assert.assertEquals(2, rows.get(0).getOperations().size());
        Assert.assertTrue(rows.get(0).getOperations().contains("H2/JDBC/PreparedStatement/executeQuery"));
        Assert.assertTrue(rows.get(0).getOperations().contains("H2/JDBC/PreparedStatement/execute"));
        Assert.assertFalse(rows.get(0).isOperationsTruncated());
    }

    @Test
    public void truncatesOperationsBeyondCapAndFlagsIt() {
        final EdgeMetricsAggregator aggregator = newAggregator();
        final long t = now();
        // 20 个不同操作名，远超每边 8 个的上限
        for (int i = 0; i < 20; i++) {
            aggregator.onSegment(segmentWithExits("GET:/a",
                    exit("op-" + i, COMPONENT_H2, SpanLayer.Database, t, t + 10L, false)));
        }

        final EdgeRow row = aggregator.snapshot().get(0);
        Assert.assertTrue("操作名上限应生效", row.getOperations().size() <= 8);
        Assert.assertTrue("截断必须打标记，页面才能提示", row.isOperationsTruncated());
    }
}
