package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class SlowRuleParserTest {

    @Test
    public void parseOperationAndUrlRules() {
        final List<SlowRule> rules = SlowRuleParser.parse(
                "operation:GET:/api/order/*=8000;url:.*/export/.*=60000");
        assertEquals(2, rules.size());
        assertEquals(SlowRule.MatchType.OPERATION, rules.get(0).getMatchType());
        assertEquals("GET:/api/order/*", rules.get(0).getPattern());
        assertEquals(8000L, rules.get(0).getThresholdMs());
        assertEquals(SlowRule.MatchType.URL, rules.get(1).getMatchType());
        assertEquals(".*/export/.*", rules.get(1).getPattern());
        assertEquals(60000L, rules.get(1).getThresholdMs());
    }
 
    @Test
    public void ignoreInvalidSegments() {
        assertTrue(SlowRuleParser.parse("").isEmpty());
        assertTrue(SlowRuleParser.parse("invalid").isEmpty());
        assertTrue(SlowRuleParser.parse("operation:GET:/api/test=abc").isEmpty());
    }
}
