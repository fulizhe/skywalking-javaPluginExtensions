package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class ErrorIgnoreRuleParserTest {

    @Test
    public void parseEmptyReturnsEmptyList() {
        assertTrue(ErrorIgnoreRuleParser.parse(null).isEmpty());
        assertTrue(ErrorIgnoreRuleParser.parse("").isEmpty());
        assertTrue(ErrorIgnoreRuleParser.parse("   ").isEmpty());
    }

    @Test
    public void parseOperationAndUrlRules() {
        final List<ErrorIgnoreRule> rules = ErrorIgnoreRuleParser.parse(
                "operation:GET:/api/exists/*=404;url:.*/probe/.*=404,410");
        assertEquals(2, rules.size());
        assertEquals(ErrorIgnoreRule.MatchType.OPERATION, rules.get(0).getMatchType());
        assertEquals("GET:/api/exists/*", rules.get(0).getPattern());
        assertTrue(rules.get(0).allowsStatus(404));
        assertEquals(ErrorIgnoreRule.MatchType.URL, rules.get(1).getMatchType());
        assertTrue(rules.get(1).allowsStatus(404));
        assertTrue(rules.get(1).allowsStatus(410));
    }

    @Test
    public void ignoreInvalidSegments() {
        final List<ErrorIgnoreRule> rules = ErrorIgnoreRuleParser.parse(
                "invalid;operation:GET:/x/*=abc;operation:GET:/ok/*=404");
        assertEquals(1, rules.size());
        assertEquals("GET:/ok/*", rules.get(0).getPattern());
    }
}
