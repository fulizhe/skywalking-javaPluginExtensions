package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.EnumSet;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

public class TraceAlertMetricsTest {

    @Before
    public void reset() {
        TraceAlertMetrics.resetForTest();
    }

    @Test
    public void snapshotContainsHttpCounters() {
        final TraceAlertMetrics metrics = TraceAlertMetrics.get();
        metrics.recordHttpAttempt();
        metrics.recordHttpSuccess("trace-1", "http://127.0.0.1:9600/test");
        metrics.recordHttpAttempt();
        metrics.recordHttpFailure("trace-2", "http://127.0.0.1:9600/test", 500, "HTTP 500");

        @SuppressWarnings("unchecked")
        final Map<String, Object> http = (Map<String, Object>) metrics.snapshot().get("httpWebhook");
        assertNotNull(http);
        assertEquals(2L, http.get("totalAttempts"));
        assertEquals(1L, http.get("successCount"));
        assertEquals(1L, http.get("failureCount"));
        assertEquals("50.00", http.get("successRatePercent"));
    }

    @Test
    public void snapshotCountsSlowAndErrorDispatches() {
        final TraceAlertMetrics metrics = TraceAlertMetrics.get();
        metrics.recordDispatchSubmitted(EnumSet.of(AlertType.SLOW));
        metrics.recordDispatchSubmitted(EnumSet.of(AlertType.ERROR));
        metrics.recordDispatchSubmitted(EnumSet.of(AlertType.SLOW, AlertType.ERROR));

        @SuppressWarnings("unchecked")
        final Map<String, Object> dispatcher = (Map<String, Object>) metrics.snapshot().get("dispatcher");
        assertNotNull(dispatcher);
        assertEquals(3L, dispatcher.get("dispatchSubmitted"));
        assertEquals(2L, dispatcher.get("dispatchSlowCount"));
        assertEquals(2L, dispatcher.get("dispatchErrorCount"));
    }

    @Test
    public void configSnapshotIsPresent() {
        final Map<String, Object> config = TraceAlertMetrics.buildConfigSnapshot();
        assertNotNull(config.get("enabled"));
        assertNotNull(config.get("webhookResolvedUrl"));
    }
}
