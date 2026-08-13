package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UrlPathExtractorTest {

    @Test
    public void extractPathFromFullUrl() {
        assertEquals("/inner/business-test/a",
                UrlPathExtractor.extractPath("http://localhost:8080/inner/business-test/a?x=1"));
    }

    @Test
    public void extractPathFromRelativePath() {
        assertEquals("/api/order/1", UrlPathExtractor.extractPath("/api/order/1"));
    }

    @Test
    public void urlAntMatchesExtractedPathOnly() {
        final CompiledAntPattern matcher = CompiledAntPattern.compile("/inner/business-test/**");
        assertTrue(matcher.matches(UrlPathExtractor.extractPath(
                "http://host:8080/inner/business-test/probe")));
        assertTrue(matcher.matches(UrlPathExtractor.extractPath("/inner/business-test/probe")));
    }
}
