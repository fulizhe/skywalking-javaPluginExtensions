package org.apache.skywalking.apm.agent.core.reporter.logfile;

import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegmentRef;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;
import org.junit.Assert;
import org.junit.Test;

/**
 * 孤段过滤判定（{@link LogFileTraceSegmentServiceClient#isOrphanSegment}）单测。
 * <p>
 * 孤段 = 无 Entry span 且无 ref（无 trace 上下文时 agent 自建的 DB/pool 段）；
 * 有 ref 的跨线程/跨进程子段（异步子段）不算孤段，需保留。
 * </p>
 */
public class LogFileTraceSegmentServiceClientOrphanTest {

    private static SegmentObject obj(final SpanType type) {
        return SegmentObject.newBuilder().setTraceId("t").setTraceSegmentId("s").setService("svc")
                .addSpans(SpanObject.newBuilder().setSpanId(0).setParentSpanId(-1).setOperationName("op")
                        .setSpanType(type).build())
                .build();
    }

    private static TraceSegment rawNoRef() {
        return new TraceSegment();
    }

    private static TraceSegment rawWithRef() {
        final TraceSegment segment = new TraceSegment();
        segment.ref(new TraceSegmentRef(new ContextCarrier()));
        return segment;
    }

    @Test
    public void entrySegment_kept() {
        Assert.assertFalse("有 Entry 的段不是孤段",
                LogFileTraceSegmentServiceClient.isOrphanSegment(rawNoRef(), obj(SpanType.Entry)));
    }

    @Test
    public void rootLocalSegment_orphan() {
        Assert.assertTrue("无上下文的 Local 根段应判为孤段",
                LogFileTraceSegmentServiceClient.isOrphanSegment(rawNoRef(), obj(SpanType.Local)));
    }

    @Test
    public void rootExitSegment_orphan() {
        Assert.assertTrue("无上下文的 Exit 根段应判为孤段",
                LogFileTraceSegmentServiceClient.isOrphanSegment(rawNoRef(), obj(SpanType.Exit)));
    }

    @Test
    public void asyncChildWithRef_kept() {
        Assert.assertFalse("带 ref 的异步子段（Local）不是孤段，需保留",
                LogFileTraceSegmentServiceClient.isOrphanSegment(rawWithRef(), obj(SpanType.Local)));
    }

    /**
     * 真实请求形态（不该误伤）：Entry 根段 + 其跨线程异步派发子段。
     * <p>
     * 样本 gisserver trace {@code …86.17906693971691047}：A = Entry（GET 地形瓦片），
     * B = Local {@code UndertowDispatch}（ref → A）。正常请求两段都应保留。
     * </p>
     */
    @Test
    public void realRequestEntryWithAsyncDispatch_kept() {
        Assert.assertFalse("Entry 根段不该被误判为孤段",
                LogFileTraceSegmentServiceClient.isOrphanSegment(rawNoRef(), obj(SpanType.Entry)));
        Assert.assertFalse("Entry 派生的跨线程异步子段（有 ref）不该被误判为孤段",
                LogFileTraceSegmentServiceClient.isOrphanSegment(rawWithRef(), obj(SpanType.Local)));
    }

    @Test
    public void nullObject_notOrphan() {
        Assert.assertFalse("null segment 不判为孤段",
                LogFileTraceSegmentServiceClient.isOrphanSegment(rawNoRef(), null));
    }
}
