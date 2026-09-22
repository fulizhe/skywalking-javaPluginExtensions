package org.apache.skywalking.apm.agent.core.reporter.logfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.skywalking.apm.dependencies.com.google.protobuf.TextFormat;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
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
        spanInfo.setLogList(span.getLogsList().stream().map(TextFormat::printToString).collect(Collectors.toList()));
        spanInfo.setTagList(tagsToTagList(span.getTagsList()));
        return spanInfo;
    }

    private static List<Map<String, Object>> tagsToTagList(final List<KeyStringValuePair> tags) {
        final List<Map<String, Object>> tagList = new ArrayList<Map<String, Object>>();
        if (tags != null) {
            for (final KeyStringValuePair tag : tags) {
                final Map<String, Object> tagMap = new HashMap<String, Object>();
                tagMap.put("tag-key", tag.getKey());
                tagMap.put("tag-value", tag.getValue());
                tagList.add(tagMap);
            }
        }
        return tagList;
    }
}
