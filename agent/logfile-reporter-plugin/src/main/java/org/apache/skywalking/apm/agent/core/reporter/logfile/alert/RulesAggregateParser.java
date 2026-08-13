package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 统一解析 slow_rules 与 error_ignore_rules，启动期预编译 Ant Matcher 并分配全局 ruleIndex。
 */
final class RulesAggregateParser {

    private RulesAggregateParser() {
    }

    static RulesEngine parse(final String slowRulesRaw, final String errorIgnoreRulesRaw) {
        final AntPatternCache patternCache = new AntPatternCache();
        final List<RulesEngine.SlowCompiledRule> slowRules = parseSlowRules(slowRulesRaw, 0, patternCache);
        final int errorStartIndex = slowRules.size();
        final List<RulesEngine.ErrorIgnoreCompiledRule> errorIgnoreRules = parseErrorIgnoreRules(errorIgnoreRulesRaw,
                errorStartIndex, patternCache);
        final List<RulesEngine.AlertRuleBinding> bindings = buildBindings(slowRules, errorIgnoreRules);
        return new RulesEngine(slowRules, errorIgnoreRules, bindings, patternCache);
    }

    private static List<RulesEngine.SlowCompiledRule> parseSlowRules(final String raw, final int startIndex,
            final AntPatternCache patternCache) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        final List<RulesEngine.SlowCompiledRule> rules = new ArrayList<>();
        int index = startIndex;
        for (String part : raw.split(";")) {
            final String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final int eqIndex = trimmed.lastIndexOf('=');
            if (eqIndex <= 0 || eqIndex == trimmed.length() - 1) {
                continue;
            }
            final ParsedRuleHeader header = parseRuleHeader(trimmed.substring(0, eqIndex).trim());
            if (header == null) {
                continue;
            }
            try {
                final long thresholdMs = Long.parseLong(trimmed.substring(eqIndex + 1).trim());
                if (thresholdMs <= 0) {
                    continue;
                }
                rules.add(new RulesEngine.SlowCompiledRule(index, trimmed, header.matchKind,
                        patternCache.get(header.pattern), thresholdMs));
                index++;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return rules;
    }

    private static List<RulesEngine.ErrorIgnoreCompiledRule> parseErrorIgnoreRules(final String raw,
            final int startIndex, final AntPatternCache patternCache) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        final List<RulesEngine.ErrorIgnoreCompiledRule> rules = new ArrayList<>();
        int index = startIndex;
        for (String part : raw.split(";")) {
            final String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final int eqIndex = trimmed.lastIndexOf('=');
            if (eqIndex <= 0 || eqIndex == trimmed.length() - 1) {
                continue;
            }
            final ParsedRuleHeader header = parseRuleHeader(trimmed.substring(0, eqIndex).trim());
            if (header == null) {
                continue;
            }
            final Set<Integer> statusCodes = parseStatusCodes(trimmed.substring(eqIndex + 1).trim());
            if (statusCodes.isEmpty()) {
                continue;
            }
            try {
                rules.add(new RulesEngine.ErrorIgnoreCompiledRule(index, trimmed, header.matchKind,
                        patternCache.get(header.pattern), new RulesEngine.StatusCodeAllowlist(statusCodes)));
                index++;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return rules;
    }

    private static ParsedRuleHeader parseRuleHeader(final String left) {
        final int colonIndex = left.indexOf(':');
        if (colonIndex <= 0 || colonIndex == left.length() - 1) {
            return null;
        }
        final String typeText = left.substring(0, colonIndex).trim();
        final String pattern = left.substring(colonIndex + 1).trim();
        if (pattern.isEmpty()) {
            return null;
        }
        if ("operation".equalsIgnoreCase(typeText)) {
            return new ParsedRuleHeader(RulesEngine.MatchKind.OPERATION, pattern);
        }
        if ("url".equalsIgnoreCase(typeText)) {
            return new ParsedRuleHeader(RulesEngine.MatchKind.URL, pattern);
        }
        return null;
    }

    private static Set<Integer> parseStatusCodes(final String statusText) {
        final Set<Integer> codes = new HashSet<>();
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

    private static List<RulesEngine.AlertRuleBinding> buildBindings(final List<RulesEngine.SlowCompiledRule> slowRules,
            final List<RulesEngine.ErrorIgnoreCompiledRule> errorIgnoreRules) {
        final List<RulesEngine.AlertRuleBinding> bindings = new ArrayList<>();
        for (RulesEngine.SlowCompiledRule rule : slowRules) {
            bindings.add(new RulesEngine.AlertRuleBinding(rule.getIndex(), rule.getDescriptor(), RulesEngine.AlertRuleType.SLOW));
        }
        for (RulesEngine.ErrorIgnoreCompiledRule rule : errorIgnoreRules) {
            bindings.add(new RulesEngine.AlertRuleBinding(rule.getIndex(), rule.getDescriptor(), RulesEngine.AlertRuleType.ERROR_IGNORE));
        }
        return bindings;
    }

    private static final class ParsedRuleHeader {
        private final RulesEngine.MatchKind matchKind;
        private final String pattern;

        private ParsedRuleHeader(final RulesEngine.MatchKind matchKind, final String pattern) {
            this.matchKind = matchKind;
            this.pattern = pattern;
        }
    }
}
