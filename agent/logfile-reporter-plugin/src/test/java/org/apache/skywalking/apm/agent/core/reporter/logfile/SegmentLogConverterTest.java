package org.apache.skywalking.apm.agent.core.reporter.logfile;

import java.util.List;
import java.util.Map;

import org.apache.skywalking.apm.network.language.agent.v3.RefType;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentReference;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;
import org.junit.Assert;
import org.junit.Test;

/**
 * {@link SegmentLogConverter} 的 refs 转换单测。
 * <p>
 * segment 级 ref 不序列化，插件侧只能看到 span 级 refs（{@code SpanObject.getRefsList()}）；
 * 本测试校验其字段口径与 {@link Log} 的 toMap/fromMap 往返。
 * </p>
 */
public class SegmentLogConverterTest {

    private static SegmentReference ref() {
        return SegmentReference.newBuilder()
                .setRefType(RefType.CrossProcess)
                .setTraceId("trace-1")
                .setParentTraceSegmentId("parent-seg")
                .setParentSpanId(3)
                .setParentService("upstream-svc")
                .setParentServiceInstance("upstream-inst")
                .setParentEndpoint("GET:/upstream")
                .setNetworkAddressUsedAtPeer("127.0.0.1:8080")
                .build();
    }

    private static SegmentObject segmentWithRefs() {
        final SpanObject span = SpanObject.newBuilder()
                .setSpanId(0)
                .setParentSpanId(-1)
                .setOperationName("entry-op")
                .setSpanType(SpanType.Entry)
                .addRefs(ref())
                .build();
        return SegmentObject.newBuilder()
                .setTraceId("trace-1")
                .setTraceSegmentId("seg-1")
                .setService("svc")
                .setServiceInstance("inst")
                .addSpans(span)
                .build();
    }

    private static SegmentObject segmentWithoutRefs() {
        final SpanObject span = SpanObject.newBuilder()
                .setSpanId(0)
                .setParentSpanId(-1)
                .setOperationName("local-op")
                .setSpanType(SpanType.Local)
                .build();
        return SegmentObject.newBuilder()
                .setTraceId("trace-2")
                .setTraceSegmentId("seg-2")
                .setService("svc")
                .setServiceInstance("inst")
                .addSpans(span)
                .build();
    }

    @Test
    public void toLog_mapsAllRefFields() {
        final Log log = SegmentLogConverter.toLog(segmentWithRefs());
        final List<Map<String, Object>> refs = log.getSpans().get(0).getRefs();
        Assert.assertNotNull("无 refs 也应是非 null 的（空）集合", refs);
        Assert.assertEquals(1, refs.size());
        final Map<String, Object> ref = refs.get(0);
        Assert.assertEquals("CrossProcess", ref.get("refType"));
        Assert.assertEquals("trace-1", ref.get("traceId"));
        Assert.assertEquals("parent-seg", ref.get("parentTraceSegmentId"));
        Assert.assertEquals(3, ref.get("parentSpanId"));
        Assert.assertEquals("upstream-svc", ref.get("parentService"));
        Assert.assertEquals("upstream-inst", ref.get("parentServiceInstance"));
        Assert.assertEquals("GET:/upstream", ref.get("parentEndpoint"));
        Assert.assertEquals("127.0.0.1:8080", ref.get("networkAddressUsedAtPeer"));
    }

    @Test
    public void toLog_noRefs_emptyList() {
        final Log log = SegmentLogConverter.toLog(segmentWithoutRefs());
        final List<Map<String, Object>> refs = log.getSpans().get(0).getRefs();
        Assert.assertNotNull(refs);
        Assert.assertTrue("无 ref 的 span 应得到空列表", refs.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void refs_surviveToMapFromMapRoundTrip() {
        final Log log = SegmentLogConverter.toLog(segmentWithRefs());
        final Log restored = Log.fromMap(log.toMap());
        final List<Map<String, Object>> refs = restored.getSpans().get(0).getRefs();
        Assert.assertNotNull(refs);
        Assert.assertEquals(1, refs.size());
        Assert.assertEquals("CrossProcess", refs.get(0).get("refType"));
        Assert.assertEquals("parent-seg", refs.get(0).get("parentTraceSegmentId"));
    }
}
