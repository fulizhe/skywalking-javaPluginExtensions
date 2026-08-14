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
import org.junit.Before;
import org.junit.Test;

public class TraceEvaluatorTest {

    @Before
    public void reset() {
        TraceAlertMetrics.resetForTest();
    }

    @Test
    public void detectErrorBySpanIsError() {
        final TraceEvaluator evaluator = evaluator(null, 1000L, 500, true, true,
                Collections.<TraceAnomalyListener>emptyList());
        final TraceSnapshot snapshot = snapshotWithSpan(entrySpan("GET:/api/test", 0, 100, false));
        assertFalse(evaluator.evaluate(snapshot).hasAlert());

        final TraceSnapshot errorSnapshot = snapshotWithSpan(entrySpan("GET:/api/test", 0, 100, true));
        assertTrue(errorSnapshot.getLogs().get(0).getSpans().get(0).getIsError());
        assertTrue(evaluator.evaluate(errorSnapshot).getAlertTypes().contains(AlertType.ERROR));
    }

    @Test
    public void detectErrorByHttpStatusCode() {
        final TraceEvaluator evaluator = evaluator(null, 1000L, 500, false, true,
                Collections.<TraceAnomalyListener>emptyList());
        final Log.SpanInfo span = entrySpan("GET:/api/test", 0, 100, false);
        span.setTagList(tag("http.status_code", "503"));
        assertTrue(evaluator.evaluate(snapshotWithSpan(span)).getAlertTypes().contains(AlertType.ERROR));
    }

    @Test
    public void ignore404WhenOperationRuleMatches() {
        final RulesEngine rulesEngine = RulesEngine.fromConfig(null,
                "operation:GET:/api/exists/**=404");
        final TraceEvaluator evaluator = evaluator(rulesEngine, 1000L, 500, true, true,
                Collections.<TraceAnomalyListener>emptyList());
        final Log.SpanInfo span = entrySpan("GET:/api/exists/123", 0, 100, true);
        span.setTagList(tag("http.status_code", "404"));
        assertFalse(evaluator.evaluate(snapshotWithSpan(span)).getAlertTypes().contains(AlertType.ERROR));
    }

    @Test
    public void stillAlert500WhenOperationRuleOnlyAllows404() {
        final RulesEngine rulesEngine = RulesEngine.fromConfig(null,
                "operation:GET:/api/exists/**=404");
        final TraceEvaluator evaluator = evaluator(rulesEngine, 1000L, 500, true, true,
                Collections.<TraceAnomalyListener>emptyList());
        final Log.SpanInfo span = entrySpan("GET:/api/exists/123", 0, 100, true);
        span.setTagList(tag("http.status_code", "500"));
        assertTrue(evaluator.evaluate(snapshotWithSpan(span)).getAlertTypes().contains(AlertType.ERROR));
    }

    @Test
    public void stillAlert404WhenIsErrorWithoutHttpStatusTag() {
        final RulesEngine rulesEngine = RulesEngine.fromConfig(null,
                "operation:GET:/api/exists/**=404");
        final TraceEvaluator evaluator = evaluator(rulesEngine, 1000L, 500, true, true,
                Collections.<TraceAnomalyListener>emptyList());
        assertTrue(evaluator.evaluate(snapshotWithSpan(entrySpan("GET:/api/exists/123", 0, 100, true)))
                .getAlertTypes().contains(AlertType.ERROR));
    }

    @Test
    public void detectSlowByDefaultThreshold() {
        final TraceEvaluator evaluator = evaluator(null, 1000L, 500, true, true,
                Collections.<TraceAnomalyListener>emptyList());
        final TraceSnapshot snapshot = snapshotWithSpan(entrySpan("GET:/api/test", 0, 1500, false));
        assertTrue(evaluator.evaluate(snapshot).getAlertTypes().contains(AlertType.SLOW));
        assertEquals(1500L, evaluator.evaluate(snapshot).getDurationMs());
    }

    @Test
    public void detectSlowByAntOperationRule() {
        final RulesEngine rulesEngine = RulesEngine.fromConfig("operation:GET:/api/order/**=8000", null);
        final TraceEvaluator evaluator = evaluator(rulesEngine, 1000L, 500, true, true,
                Collections.<TraceAnomalyListener>emptyList());
        assertFalse(evaluator.evaluate(snapshotWithSpan(entrySpan("GET:/api/order/123", 0, 5000, false)))
                .getAlertTypes().contains(AlertType.SLOW));

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
        final TraceEvaluator evaluator = evaluator(null, 100000L, 500, false, false,
                Collections.singletonList(listener));
        final TraceSnapshot snapshot = snapshotWithSpan(entrySpan("GET:/custom", 0, 20, false));
        assertTrue(evaluator.evaluate(snapshot).getAlertTypes().contains(AlertType.ERROR));
        assertTrue(evaluator.evaluate(snapshot).getAlertTypes().contains(AlertType.SLOW));
    }

    @Test
    public void recordsPerRuleHitCountWhenIgnored() {
        final RulesEngine rulesEngine = RulesEngine.fromConfig(null,
                "operation:GET:/api/a/**=404;operation:GET:/api/b/**=404");
        TraceAlertMetrics.get().bindRules(rulesEngine);
        final TraceEvaluator evaluator = evaluator(rulesEngine, 1000L, 500, true, true,
                Collections.<TraceAnomalyListener>emptyList());

        final Log.SpanInfo spanA = entrySpan("GET:/api/a/1", 0, 100, true);
        spanA.setTagList(tag("http.status_code", "404"));
        evaluator.evaluate(snapshotWithSpan(spanA));

        final Log.SpanInfo spanB = entrySpan("GET:/api/b/2", 0, 100, true);
        spanB.setTagList(tag("http.status_code", "404"));
        evaluator.evaluate(snapshotWithSpan(spanB));
        evaluator.evaluate(snapshotWithSpan(spanB));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> items = (List<Map<String, Object>>) TraceAlertMetrics.get().snapshot()
                .get("rules");
        assertEquals(2, items.size());
        assertEquals(0, items.get(0).get("ruleIndex"));
        assertEquals("ERROR_IGNORE", items.get(0).get("type"));
        assertEquals("ant", items.get(0).get("syntax"));
        assertEquals(1L, items.get(0).get("hitCount"));
        assertEquals(1, items.get(1).get("ruleIndex"));
        assertEquals(2L, items.get(1).get("hitCount"));
    }

    @Test
    public void recordsSlowRuleHitWhenThresholdMatched() {
        final RulesEngine rulesEngine = RulesEngine.fromConfig("operation:GET:/api/order/**=8000", null);
        TraceAlertMetrics.get().bindRules(rulesEngine);
        final TraceEvaluator evaluator = evaluator(rulesEngine, 1000L, 500, true, true,
                Collections.<TraceAnomalyListener>emptyList());
        evaluator.evaluate(snapshotWithSpan(entrySpan("GET:/api/order/123", 0, 9000, false)));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> items = (List<Map<String, Object>>) TraceAlertMetrics.get().snapshot()
                .get("rules");
        assertEquals(1, items.size());
        assertEquals("SLOW", items.get(0).get("type"));
        assertEquals(1L, items.get(0).get("hitCount"));
    }

    private TraceEvaluator evaluator(final RulesEngine rulesEngine, final long defaultSlowMs, final int httpMin,
            final boolean enableSpanIsError, final boolean enableHttpStatusError,
            final List<TraceAnomalyListener> listeners) {
        final RulesEngine engine = rulesEngine == null ? new RulesEngine(null, null) : rulesEngine;
        return new TraceEvaluator(engine, defaultSlowMs, httpMin, enableSpanIsError, enableHttpStatusError, listeners);
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
