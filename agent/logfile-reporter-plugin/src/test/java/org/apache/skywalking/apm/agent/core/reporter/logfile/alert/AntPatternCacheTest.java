package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AntPatternCacheTest {

    @Test
    public void samePatternCompilesOnce() {
        final AntPatternCache cache = new AntPatternCache();
        final CompiledAntPattern first = cache.get("/api/**");
        final CompiledAntPattern second = cache.get("/api/**");
        assertTrue(first == second);
        assertEquals(1, cache.getCompileCountForTest());
        assertEquals(1, cache.size());

        first.matches("/api/order/1");
        second.matches("/api/order/2");
        assertEquals(1, cache.getCompileCountForTest());
    }

    @Test
    public void differentPatternsCompileSeparately() {
        final AntPatternCache cache = new AntPatternCache();
        cache.get("/api/**");
        cache.get("/inner/**");
        assertEquals(2, cache.getCompileCountForTest());
        assertEquals(2, cache.size());
    }
}
