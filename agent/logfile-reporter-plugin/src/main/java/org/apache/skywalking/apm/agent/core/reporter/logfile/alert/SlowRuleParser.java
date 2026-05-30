package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 解析 plugin.logfilereporter.alert.slow_rules 配置，多条规则以分号分隔。
 */
final class SlowRuleParser {

    private SlowRuleParser() {
    }

    static List<SlowRule> parse(final String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        final List<SlowRule> rules = new ArrayList<>();
        for (String part : raw.split(";")) {
            final String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final int eqIndex = trimmed.lastIndexOf('=');
            if (eqIndex <= 0 || eqIndex == trimmed.length() - 1) {
                continue;
            }
            final String left = trimmed.substring(0, eqIndex).trim();
            final String thresholdText = trimmed.substring(eqIndex + 1).trim();
            final int colonIndex = left.indexOf(':');
            if (colonIndex <= 0 || colonIndex == left.length() - 1) {
                continue;
            }
            final String typeText = left.substring(0, colonIndex).trim();
            final String pattern = left.substring(colonIndex + 1).trim();
            final SlowRule.MatchType matchType;
            if ("operation".equalsIgnoreCase(typeText)) {
                matchType = SlowRule.MatchType.OPERATION;
            } else if ("url".equalsIgnoreCase(typeText)) {
                matchType = SlowRule.MatchType.URL;
            } else {
                continue;
            }
            try {
                final long thresholdMs = Long.parseLong(thresholdText);
                if (thresholdMs > 0) {
                    rules.add(new SlowRule(matchType, pattern, thresholdMs));
                }
            } catch (NumberFormatException ignored) {
                // skip invalid threshold
            }
        }
        return rules;
    }
}
