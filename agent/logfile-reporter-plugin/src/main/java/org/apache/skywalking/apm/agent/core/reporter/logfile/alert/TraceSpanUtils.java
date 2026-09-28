package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.Map;

import org.apache.skywalking.apm.agent.core.reporter.logfile.Log;

/**
 * Span 元数据提取与 Entry Span 识别。
 */
final class TraceSpanUtils {

    static final String TAG_URL = "url";
    static final String TAG_HTTP_STATUS = "http.status_code";
    static final String SPAN_TYPE_ENTRY = "Entry";

    private TraceSpanUtils() {
    }

    static String getTagValue(final Log.SpanInfo span, final String key) {
        if (span == null || key == null || span.getTagList() == null) {
            return null;
        }
        for (Map<String, Object> tag : span.getTagList()) {
            if (key.equals(tag.get("tag-key"))) {
                final Object value = tag.get("tag-value");
                return value == null ? null : String.valueOf(value);
            }
        }
        return null;
    }

    static boolean isEntrySpan(final Log.SpanInfo span) {
        if (span == null) {
            return false;
        }
        if (SPAN_TYPE_ENTRY.equalsIgnoreCase(span.getSpanType())) {
            return true;
        }
        return span.getParentSpanId() == -1;
    }

    static long spanDurationMs(final Log.SpanInfo span) {
        if (span == null) {
            return 0L;
        }
        final long duration = span.getEndTime() - span.getStartTime();
        return duration < 0 ? 0L : duration;
    }

    static Log.SpanInfo findPrimaryEntrySpan(final TraceSnapshot snapshot) {
        Log.SpanInfo fallback = null;
        long maxDuration = -1L;
        for (Log log : snapshot.getLogs()) {
            if (log.getSpans() == null) {
                continue;
            }
            for (Log.SpanInfo span : log.getSpans()) {
                if (isEntrySpan(span)) {
                    return span;
                }
                final long duration = spanDurationMs(span);
                if (duration > maxDuration) {
                    maxDuration = duration;
                    fallback = span;
                }
            }
        }
        return fallback;
    }

    static long maxDurationMs(final TraceSnapshot snapshot) {
        long max = 0L;
        for (Log log : snapshot.getLogs()) {
            if (log.getSpans() == null) {
                continue;
            }
            for (Log.SpanInfo span : log.getSpans()) {
                final long duration = spanDurationMs(span);
                if (duration > max) {
                    max = duration;
                }
            }
        }
        return max;
    }

    static int countErrorSpans(final TraceSnapshot snapshot) {
        int count = 0;
        for (Log log : snapshot.getLogs()) {
            if (log.getSpans() == null) {
                continue;
            }
            for (Log.SpanInfo span : log.getSpans()) {
                if (span.getIsError()) {
                    count++;
                }
            }
        }
        return count;
    }
}
