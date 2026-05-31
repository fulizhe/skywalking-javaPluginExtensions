package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class AntPatternCacheTest {

    @Before
    public void resetCache() {
        AntPatternCache.resetForTest();
    }

    @Test
    public void samePatternCompilesOnce() {
        final CompiledAntPattern first = AntPatternCache.get("/api/**");
        final CompiledAntPattern second = AntPatternCache.get("/api/**");
        assertTrue(first == second);
        assertEquals(1, AntPatternCache.getCompileCountForTest());
        assertEquals(1, AntPatternCache.cacheSize());

        first.matches("/api/order/1");
        second.matches("/api/order/2");
        assertEquals(1, AntPatternCache.getCompileCountForTest());
    }

    @Test
    public void differentPatternsCompileSeparately() {
        AntPatternCache.get("/api/**");
        AntPatternCache.get("/inner/**");
        assertEquals(2, AntPatternCache.getCompileCountForTest());
        assertEquals(2, AntPatternCache.cacheSize());
    }
}
