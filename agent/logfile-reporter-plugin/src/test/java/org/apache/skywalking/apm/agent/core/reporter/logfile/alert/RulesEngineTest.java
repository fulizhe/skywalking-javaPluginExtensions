package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RulesEngineTest {

    @Test
    public void slowAntOperationOverridesDefault() {
        final RulesEngine engine = RulesEngine.fromConfig("operation:GET:/api/order/**=8000", null);
        final RulesEngine.SlowMatchResult match = engine.matchSlow("GET:/api/order/1", null, 3000L);
        assertEquals(8000L, match.getThresholdMs());
        assertEquals(0, match.getRuleIndex());
        assertEquals(3000L, engine.matchSlow("GET:/api/other/1", null, 3000L).getThresholdMs());
    }

    @Test
    public void slowOperationPriorityOverUrl() {
        final RulesEngine engine = RulesEngine.fromConfig(
                "url:/export/**=60000;operation:GET:/api/order/**=8000", null);
        assertEquals(8000L, engine.matchSlow("GET:/api/order/1", "http://host/export/report", 3000L).getThresholdMs());
    }

    @Test
    public void slowLaterRuleOverridesWithinSameKind() {
        final RulesEngine engine = RulesEngine.fromConfig(
                "operation:GET:/api/**=5000;operation:GET:/api/order/**=8000", null);
        assertEquals(8000L, engine.matchSlow("GET:/api/order/1", null, 1000L).getThresholdMs());
    }

    @Test
    public void errorIgnoreAntOperation404() {
        final RulesEngine engine = RulesEngine.fromConfig(null, "operation:GET:/api/exists/**=404");
        assertEquals(0, engine.matchErrorIgnoreRuleIndex("GET:/api/exists/123", null, 404));
        assertEquals(-1, engine.matchErrorIgnoreRuleIndex("GET:/api/exists/123", null, 500));
    }

    @Test
    public void errorIgnoreUrlAntWithPathExtraction() {
        final RulesEngine engine = RulesEngine.fromConfig(null,
                "url:/inner/business-test/**=404,410");
        final String url = "http://localhost:8080/inner/business-test/probe";
        assertEquals(0, engine.matchErrorIgnoreRuleIndex("GET:/x", url, 404));
        assertTrue(engine.getErrorIgnoreRules().get(0).allowsStatus(410));
    }

    @Test
    public void globalRuleIndexSpansSlowAndErrorIgnore() {
        final RulesEngine engine = RulesEngine.fromConfig(
                "operation:GET:/slow/**=8000",
                "operation:GET:/api/exists/**=404");
        assertEquals(2, engine.getTotalRuleCount());
        assertEquals(0, engine.getRuleBindings().get(0).getRuleIndex());
        assertEquals(RulesEngine.AlertRuleType.SLOW, engine.getRuleBindings().get(0).getType());
        assertEquals(1, engine.getRuleBindings().get(1).getRuleIndex());
        assertEquals(RulesEngine.AlertRuleType.ERROR_IGNORE, engine.getRuleBindings().get(1).getType());
        assertEquals(1, engine.matchErrorIgnoreRuleIndex("GET:/api/exists/1", null, 404));
    }
}
