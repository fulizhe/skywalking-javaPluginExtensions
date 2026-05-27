package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;

import org.junit.Test;

public class NotifiedFlagsCacheTest {

    @Test
    public void deduplicateWithinTtl() {
        final NotifiedFlagsCache cache = new NotifiedFlagsCache(60_000L);
        final String traceId = "trace-1";

        assertTrue(cache.shouldNotify(traceId, AlertType.ERROR));
        cache.markNotified(traceId, EnumSet.of(AlertType.ERROR));

        assertFalse(cache.shouldNotify(traceId, AlertType.ERROR));
        assertTrue(cache.shouldNotify(traceId, AlertType.SLOW));
    }

    @Test
    public void allowNotifyAgainAfterTtl() throws InterruptedException {
        final NotifiedFlagsCache cache = new NotifiedFlagsCache(50L);
        final String traceId = "trace-2";

        cache.markNotified(traceId, EnumSet.of(AlertType.ERROR));
        assertFalse(cache.shouldNotify(traceId, AlertType.ERROR));

        Thread.sleep(80L);

        assertTrue(cache.shouldNotify(traceId, AlertType.ERROR));
        assertEquals(0, cache.size());
    }

    @Test
    public void mergeFlagsForSameTraceWithinTtl() {
        final NotifiedFlagsCache cache = new NotifiedFlagsCache(60_000L);
        final String traceId = "trace-3";

        cache.markNotified(traceId, EnumSet.of(AlertType.ERROR));
        cache.markNotified(traceId, EnumSet.of(AlertType.SLOW));

        assertFalse(cache.shouldNotify(traceId, AlertType.ERROR));
        assertFalse(cache.shouldNotify(traceId, AlertType.SLOW));
    }
}
