package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * 错误白名单编译规则，payload 为允许的状态码集合。
 */
final class ErrorIgnoreCompiledRule extends CompiledRule {

    private final StatusCodeAllowlist allowedStatusCodes;

    ErrorIgnoreCompiledRule(final int index, final String descriptor, final MatchKind matchKind,
            final RuleSyntax syntax, final TracePatternMatcher matcher,
            final StatusCodeAllowlist allowedStatusCodes) {
        super(index, descriptor, matchKind, syntax, matcher);
        this.allowedStatusCodes = allowedStatusCodes;
    }

    boolean allowsStatus(final int statusCode) {
        return allowedStatusCodes.allows(statusCode);
    }
}
