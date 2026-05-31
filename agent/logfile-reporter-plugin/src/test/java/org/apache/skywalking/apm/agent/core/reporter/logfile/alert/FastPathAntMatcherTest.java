package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FastPathAntMatcherTest {

    @Test
    public void matchesDoubleStarAcrossSegments() {
        assertTrue(FastPathAntMatcher.match("/inner/business-test/**", "/inner/business-test/a/b"));
        assertTrue(FastPathAntMatcher.match("/api/**", "/api/order/123"));
        assertFalse(FastPathAntMatcher.match("/api/**", "/other/order"));
    }

    @Test
    public void matchesSingleStarWithinSegment() {
        assertTrue(FastPathAntMatcher.match("/api/exists/*", "/api/exists/123"));
        assertFalse(FastPathAntMatcher.match("/api/exists/*", "/api/exists/a/b"));
    }

    @Test
    public void matchesQuestionMarkSingleChar() {
        assertTrue(FastPathAntMatcher.match("/status/?00", "/status/400"));
        assertFalse(FastPathAntMatcher.match("/status/?00", "/status/4000"));
    }

    @Test
    public void matchesOperationNamePattern() {
        assertTrue(FastPathAntMatcher.match("GET:/api/order/**", "GET:/api/order/123"));
        assertFalse(FastPathAntMatcher.match("GET:/api/order/**", "POST:/api/order/123"));
    }

    @Test
    public void emptyPathEdgeCases() {
        assertTrue(FastPathAntMatcher.match("/**", "/"));
        assertFalse(FastPathAntMatcher.match("/api/*", ""));
    }
}
