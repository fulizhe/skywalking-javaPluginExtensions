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
        final List<SlowCompiledRule> slowRules = parseSlowRules(slowRulesRaw, 0);
        final int errorStartIndex = slowRules.size();
        final List<ErrorIgnoreCompiledRule> errorIgnoreRules = parseErrorIgnoreRules(errorIgnoreRulesRaw,
                errorStartIndex);
        final List<AlertRuleBinding> bindings = buildBindings(slowRules, errorIgnoreRules);
        return new RulesEngine(slowRules, errorIgnoreRules, bindings);
    }

    private static List<SlowCompiledRule> parseSlowRules(final String raw, final int startIndex) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        final List<SlowCompiledRule> rules = new ArrayList<SlowCompiledRule>();
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
            final String left = trimmed.substring(0, eqIndex).trim();
            final String thresholdText = trimmed.substring(eqIndex + 1).trim();
            final ParsedRuleHeader header = parseRuleHeader(left);
            if (header == null) {
                continue;
            }
            try {
                final long thresholdMs = Long.parseLong(thresholdText);
                if (thresholdMs <= 0) {
                    continue;
                }
                final TracePatternMatcher matcher = PatternMatcherFactory.createAnt(header.pattern);
                rules.add(new SlowCompiledRule(index, trimmed, header.matchKind, RuleSyntax.ANT, matcher, thresholdMs));
                index++;
            } catch (NumberFormatException ignored) {
                // skip invalid threshold
            } catch (IllegalArgumentException ignored) {
                // skip invalid pattern
            }
        }
        return rules;
    }

    private static List<ErrorIgnoreCompiledRule> parseErrorIgnoreRules(final String raw, final int startIndex) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        final List<ErrorIgnoreCompiledRule> rules = new ArrayList<ErrorIgnoreCompiledRule>();
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
            final String left = trimmed.substring(0, eqIndex).trim();
            final String statusText = trimmed.substring(eqIndex + 1).trim();
            final ParsedRuleHeader header = parseRuleHeader(left);
            if (header == null) {
                continue;
            }
            final Set<Integer> statusCodes = parseStatusCodes(statusText);
            if (statusCodes.isEmpty() || header.pattern.isEmpty()) {
                continue;
            }
            try {
                final TracePatternMatcher matcher = PatternMatcherFactory.createAnt(header.pattern);
                rules.add(new ErrorIgnoreCompiledRule(index, trimmed, header.matchKind, RuleSyntax.ANT, matcher,
                        new StatusCodeAllowlist(statusCodes)));
                index++;
            } catch (IllegalArgumentException ignored) {
                // skip invalid pattern
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
            return new ParsedRuleHeader(MatchKind.OPERATION, pattern);
        }
        if ("url".equalsIgnoreCase(typeText)) {
            return new ParsedRuleHeader(MatchKind.URL, pattern);
        }
        return null;
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

    private static List<AlertRuleBinding> buildBindings(final List<SlowCompiledRule> slowRules,
            final List<ErrorIgnoreCompiledRule> errorIgnoreRules) {
        final List<AlertRuleBinding> bindings = new ArrayList<AlertRuleBinding>();
        for (SlowCompiledRule rule : slowRules) {
            bindings.add(new AlertRuleBinding(rule.getIndex(), rule.getDescriptor(), AlertRuleType.SLOW));
        }
        for (ErrorIgnoreCompiledRule rule : errorIgnoreRules) {
            bindings.add(new AlertRuleBinding(rule.getIndex(), rule.getDescriptor(), AlertRuleType.ERROR_IGNORE));
        }
        return bindings;
    }

    private static final class ParsedRuleHeader {
        private final MatchKind matchKind;
        private final String pattern;

        private ParsedRuleHeader(final MatchKind matchKind, final String pattern) {
            this.matchKind = matchKind;
            this.pattern = pattern;
        }
    }
}
