package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * 慢请求匹配规则：按 operation 通配或 url 正则，命中时使用对应阈值（毫秒）。
 */
class SlowRule {

    enum MatchType {
        OPERATION,
        URL
    }

    private final MatchType matchType;
    private final String pattern;
    private final long thresholdMs;

    SlowRule(final MatchType matchType, final String pattern, final long thresholdMs) {
        this.matchType = matchType;
        this.pattern = pattern;
        this.thresholdMs = thresholdMs;
    }

    MatchType getMatchType() {
        return matchType;
    }

    String getPattern() {
        return pattern;
    }

    long getThresholdMs() {
        return thresholdMs;
    }
}
