package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.List;

/**
 * 按 operation 优先、url 次之匹配错误白名单规则。
 */
final class ErrorIgnoreRuleMatcher {

    private ErrorIgnoreRuleMatcher() {
    }

    /**
     * @return 命中规则下标（与配置顺序一致），未命中返回 -1
     */
    static int findMatchingRuleIndex(final List<ErrorIgnoreRule> rules, final String operation, final String url,
            final int statusCode) {
        if (rules == null || rules.isEmpty()) {
            return -1;
        }
        for (ErrorIgnoreRule rule : rules) {
            if (rule.getMatchType() == ErrorIgnoreRule.MatchType.OPERATION
                    && TraceSpanUtils.matchesOperationPattern(operation, rule.getPattern())
                    && rule.allowsStatus(statusCode)) {
                return rule.getIndex();
            }
        }
        for (ErrorIgnoreRule rule : rules) {
            if (rule.getMatchType() == ErrorIgnoreRule.MatchType.URL
                    && TraceSpanUtils.matchesUrlPattern(url, rule.getPattern())
                    && rule.allowsStatus(statusCode)) {
                return rule.getIndex();
            }
        }
        return -1;
    }
}
