package org.apache.skywalking.apm.agent.core.reporter.logfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.skywalking.apm.dependencies.com.google.protobuf.TextFormat;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentReference;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;

/**
 * SegmentObject → Log 转换器（从 LogFileTraceSegmentServiceClient 提取，供新旧路径共用）。
 * <p>
 * 逻辑与 {@code LogFileTraceSegmentServiceClient.segmentToLog} 一致，
 * 但不修改旧路径代码（Phase 1 零行为变化）。
 * </p>
 */
public final class SegmentLogConverter {

    private SegmentLogConverter() {
    }

    public static Log toLog(final SegmentObject segment) {
        final Log log = new Log();
        log.setTraceId(segment.getTraceId());
        log.setTraceSegmentId(segment.getTraceSegmentId());
        log.setService(segment.getService());
        log.setServiceInstance(segment.getServiceInstance());
        log.setIsSizeLimited(segment.getIsSizeLimited());
        final List<Log.SpanInfo> spanInfoList = new ArrayList<Log.SpanInfo>();
        for (final SpanObject span : segment.getSpansList()) {
            spanInfoList.add(toSpanInfo(span));
        }
        log.setSpans(spanInfoList);
        return log;
    }

    private static Log.SpanInfo toSpanInfo(final SpanObject span) {
        final Log.SpanInfo spanInfo = new Log.SpanInfo();
        spanInfo.setSpanId(span.getSpanId());
        spanInfo.setParentSpanId(span.getParentSpanId());
        spanInfo.setOperationName(span.getOperationName());
        spanInfo.setStartTime(span.getStartTime());
        spanInfo.setEndTime(span.getEndTime());
        spanInfo.setSpanType(span.getSpanType().toString());
        spanInfo.setSpanLayer(span.getSpanLayer().toString());
        spanInfo.setComponentId(span.getComponentId());
        spanInfo.setIsError(span.getIsError());
        // 手工预分配循环取代 getLogsList().stream().map(TextFormat::printToString).collect(toList())：
        // 省掉每 span 一套 stream 管道（Stream/中间节点/Collector 闭包）与 collect 触发的 ArrayList 扩容；
        // 产物不变（顺序、元素与 List<String> 类型一致）。TextFormat 转换仍在（见优化清单 P4）。
        final List<org.apache.skywalking.apm.network.language.agent.v3.Log> logs = span.getLogsList();
        final List<String> logList = new ArrayList<String>(logs.size());
        for (final org.apache.skywalking.apm.network.language.agent.v3.Log entry : logs) {
            logList.add(TextFormat.printToString(entry));
        }
        spanInfo.setLogList(logList);
        spanInfo.setTagList(tagsToTagList(span.getTagsList()));
        spanInfo.setRefs(refsToRefList(span.getRefsList()));
        return spanInfo;
    }

    private static List<Log.Tag> tagsToTagList(final List<KeyStringValuePair> tags) {
        final List<Log.Tag> tagList = new ArrayList<Log.Tag>();
        if (tags != null) {
            for (final KeyStringValuePair tag : tags) {
                tagList.add(new Log.Tag(tag.getKey(), tag.getValue()));
            }
        }
        return tagList;
    }

    /**
     * refs 转为 List&lt;Map&gt;，便于序列化与单测复用。
     * <p>
     * segment 级 ref 在 {@code TraceSegment.transform()} 里被显式跳过（{@code // Don't serialize TraceSegmentReference}），
     * 因此上报的 {@link SegmentObject} 里只有 span 级 refs，字段口径与 1.0.0 的 {@code refsToRefList} 对齐。
     * </p>
     */
    private static List<Map<String, Object>> refsToRefList(final List<SegmentReference> refs) {
        final List<Map<String, Object>> refList = new ArrayList<Map<String, Object>>();
        if (refs != null) {
            for (final SegmentReference ref : refs) {
                final Map<String, Object> refMap = new HashMap<String, Object>();
                refMap.put("refType", ref.getRefType().name());
                refMap.put("traceId", ref.getTraceId());
                refMap.put("parentTraceSegmentId", ref.getParentTraceSegmentId());
                refMap.put("parentSpanId", ref.getParentSpanId());
                refMap.put("parentService", ref.getParentService());
                refMap.put("parentServiceInstance", ref.getParentServiceInstance());
                refMap.put("parentEndpoint", ref.getParentEndpoint());
                refMap.put("networkAddressUsedAtPeer", ref.getNetworkAddressUsedAtPeer());
                refList.add(refMap);
            }
        }
        return refList;
    }
}
