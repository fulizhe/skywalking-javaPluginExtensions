package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class RulesAggregateParserTest {

    @Before
    public void resetCache() {
        AntPatternCache.resetForTest();
    }

    @Test
    public void parseAntSlowRules() {
        final RulesEngine engine = RulesAggregateParser.parse(
                "operation:GET:/api/order/**=8000;url:/export/**=60000", null);
        assertEquals(2, engine.getSlowRuleCount());
        assertEquals(8000L, engine.matchSlow("GET:/api/order/1", null, 1000L).getThresholdMs());
    }

    @Test
    public void parseAntErrorIgnoreRules() {
        final RulesEngine engine = RulesAggregateParser.parse(null,
                "operation:GET:/api/exists/**=404;url:/inner/probe/**=404,410");
        assertEquals(2, engine.getErrorIgnoreRuleCount());
        assertEquals(RuleSyntax.ANT, engine.getErrorIgnoreRules().get(0).getSyntax());
        assertEquals(RuleSyntax.ANT, engine.getErrorIgnoreRules().get(1).getSyntax());
    }

    @Test
    public void ruleIndexFollowsConfigOrderWithGlobalOffset() {
        final RulesEngine engine = RulesAggregateParser.parse(
                "operation:GET:/slow/**=8000",
                "operation:GET:/api/a/**=404;operation:GET:/api/b/**=404");
        assertEquals(0, engine.matchSlow("GET:/slow/x", null, 1000L).getRuleIndex());
        assertEquals(1, engine.matchErrorIgnoreRuleIndex("GET:/api/a/1", null, 404));
        assertEquals(2, engine.matchErrorIgnoreRuleIndex("GET:/api/b/2", null, 404));
    }

    @Test
    public void sharedAntPatternCachedOnce() {
        RulesAggregateParser.parse(null,
                "url:/same/**=404;url:/same/**=410");
        assertEquals(1, AntPatternCache.cacheSize());
        assertTrue(AntPatternCache.getCompileCountForTest() >= 1);
    }

    @Test
    public void ignoreUnknownPrefix() {
        final RulesEngine engine = RulesAggregateParser.parse(
                "operation-ant:GET:/api/order/**=8000;unknown:/export/**=60000",
                "url-ant:/exists/**=404");
        assertEquals(0, engine.getTotalRuleCount());
    }
}
