package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析 plugin.logfilereporter.alert.error_ignore_rules 配置，多条规则以分号分隔。
 */
final class ErrorIgnoreRuleParser {

    private ErrorIgnoreRuleParser() {
    }

    static List<ErrorIgnoreRule> parse(final String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        final List<ErrorIgnoreRule> rules = new ArrayList<ErrorIgnoreRule>();
        int index = 0;
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
            final String statusText = trimmed.substring(eqIndex + 1).trim();
            final int colonIndex = left.indexOf(':');
            if (colonIndex <= 0 || colonIndex == left.length() - 1) {
                continue;
            }
            final String typeText = left.substring(0, colonIndex).trim();
            final String pattern = left.substring(colonIndex + 1).trim();
            final ErrorIgnoreRule.MatchType matchType;
            if ("operation".equalsIgnoreCase(typeText)) {
                matchType = ErrorIgnoreRule.MatchType.OPERATION;
            } else if ("url".equalsIgnoreCase(typeText)) {
                matchType = ErrorIgnoreRule.MatchType.URL;
            } else {
                continue;
            }
            final Set<Integer> statusCodes = parseStatusCodes(statusText);
            if (statusCodes.isEmpty() || pattern.isEmpty()) {
                continue;
            }
            rules.add(new ErrorIgnoreRule(index, trimmed, matchType, pattern, statusCodes));
            index++;
        }
        return rules;
    }

    private static Set<Integer> parseStatusCodes(final String statusText) {
        final Set<Integer> codes = new HashSet<Integer>();
        for (String segment : statusText.split(",")) {
            final String codeText = segment.trim();
            if (codeText.isEmpty()) {
                continue;
            }
            try {
                final int code = Integer.parseInt(codeText);
                if (code >= 100 && code <= 599) {
                    codes.add(code);
                }
            } catch (NumberFormatException ignored) {
                // skip invalid segment
            }
        }
        return codes;
    }
}
