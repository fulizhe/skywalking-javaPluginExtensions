package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * 慢请求编译规则，payload 为阈值（毫秒）。
 */
final class SlowCompiledRule extends CompiledRule {

    private final long thresholdMs;

    SlowCompiledRule(final int index, final String descriptor, final MatchKind matchKind, final RuleSyntax syntax,
            final TracePatternMatcher matcher, final long thresholdMs) {
        super(index, descriptor, matchKind, syntax, matcher);
        this.thresholdMs = thresholdMs;
    }

    long getThresholdMs() {
        return thresholdMs;
    }
}
