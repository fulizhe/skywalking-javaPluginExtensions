package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.skywalking.apm.agent.core.reporter.logfile.Log;
import org.junit.Test;

public class TraceEvaluatorTest {

    @Test
    public void detectErrorBySpanIsError() {
        final TraceEvaluator evaluator = new TraceEvaluator(
                Collections.<SlowRule>emptyList(), 1000L, 500, true, true, Collections.<TraceAnomalyListener>emptyList());
        final TraceSnapshot snapshot = snapshotWithSpan(entrySpan("GET:/api/test", 0, 100, false));
        assertFalse(evaluator.evaluate(snapshot).hasAlert());

        final TraceSnapshot errorSnapshot = snapshotWithSpan(entrySpan("GET:/api/test", 0, 100, true));
        assertTrue(errorSnapshot.getLogs().get(0).getSpans().get(0).getIsError());
        assertTrue(evaluator.evaluate(errorSnapshot).getAlertTypes().contains(AlertType.ERROR));
    }

    @Test
    public void detectErrorByHttpStatusCode() {
        final TraceEvaluator evaluator = new TraceEvaluator(
                Collections.<SlowRule>emptyList(), 1000L, 500, false, true, Collections.<TraceAnomalyListener>emptyList());
        final Log.SpanInfo span = entrySpan("GET:/api/test", 0, 100, false);
        span.setTagList(tag("http.status_code", "503"));
        final TraceSnapshot snapshot = snapshotWithSpan(span);
        assertTrue(evaluator.evaluate(snapshot).getAlertTypes().contains(AlertType.ERROR));
    }

    @Test
    public void detectSlowByDefaultThreshold() {
        final TraceEvaluator evaluator = new TraceEvaluator(
                Collections.<SlowRule>emptyList(), 1000L, 500, true, true, Collections.<TraceAnomalyListener>emptyList());
        final TraceSnapshot snapshot = snapshotWithSpan(entrySpan("GET:/api/test", 0, 1500, false));
        assertTrue(evaluator.evaluate(snapshot).getAlertTypes().contains(AlertType.SLOW));
        assertEquals(1500L, evaluator.evaluate(snapshot).getDurationMs());
    }

    @Test
    public void detectSlowByOperationRule() {
        final List<SlowRule> rules = Collections.singletonList(
                new SlowRule(SlowRule.MatchType.OPERATION, "GET:/api/order/*", 8000L));
        final TraceEvaluator evaluator = new TraceEvaluator(
                rules, 1000L, 500, true, true, Collections.<TraceAnomalyListener>emptyList());
        final TraceSnapshot snapshot = snapshotWithSpan(entrySpan("GET:/api/order/123", 0, 5000, false));
        assertFalse(evaluator.evaluate(snapshot).getAlertTypes().contains(AlertType.SLOW));

        final TraceSnapshot slowSnapshot = snapshotWithSpan(entrySpan("GET:/api/order/123", 0, 9000, false));
        assertTrue(evaluator.evaluate(slowSnapshot).getAlertTypes().contains(AlertType.SLOW));
        assertEquals(8000L, evaluator.evaluate(slowSnapshot).getThresholdMs());
    }

    @Test
    public void customListenerCanMarkErrorAndSlow() {
        final TraceAnomalyListener listener = new TraceAnomalyListener() {
            @Override
            public void onTraceAlert(final TraceAlertEvent event) {
            }

            @Override
            public boolean isError(final TraceSnapshot snapshot) {
                return "GET:/custom".equals(TraceSpanUtils.findPrimaryEntrySpan(snapshot).getOperationName());
            }

            @Override
            public boolean isSlow(final TraceSnapshot snapshot, final long durationMs) {
                return durationMs > 10;
            }
        };
        final TraceEvaluator evaluator = new TraceEvaluator(
                Collections.<SlowRule>emptyList(), 100000L, 500, false, false,
                Collections.singletonList(listener));
        final TraceSnapshot snapshot = snapshotWithSpan(entrySpan("GET:/custom", 0, 20, false));
        assertTrue(evaluator.evaluate(snapshot).getAlertTypes().contains(AlertType.ERROR));
        assertTrue(evaluator.evaluate(snapshot).getAlertTypes().contains(AlertType.SLOW));
    }

    private TraceSnapshot snapshotWithSpan(final Log.SpanInfo span) {
        final Log log = new Log();
        log.setTraceId("trace-1");
        log.setService("demo-service");
        log.setServiceInstance("demo-instance");
        log.setSpans(Collections.singletonList(span));
        final Map<String, Object> traceMap = new HashMap<String, Object>();
        traceMap.put("logs", Collections.singletonList(log.toMap()));
        return new TraceSnapshot("trace-1", "demo-service", "demo-instance",
                Collections.singletonList(log), traceMap);
    }

    private Log.SpanInfo entrySpan(final String operation, final long start, final long duration, final boolean error) {
        final Log.SpanInfo span = new Log.SpanInfo();
        span.setSpanId(0);
        span.setParentSpanId(-1);
        span.setOperationName(operation);
        span.setSpanType("Entry");
        span.setStartTime(start);
        span.setEndTime(start + duration);
        span.setIsError(error);
        return span;
    }

    private List<Map<String, Object>> tag(final String key, final String value) {
        final Map<String, Object> tag = new HashMap<String, Object>();
        tag.put("tag-key", key);
        tag.put("tag-value", value);
        final List<Map<String, Object>> tags = new ArrayList<Map<String, Object>>();
        tags.add(tag);
        return tags;
    }
}
