package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 错误告警白名单：按 operation 通配或 url 正则匹配 span，且 HTTP 状态码在允许列表内时不参与 trace 级错误判定。
 */
class ErrorIgnoreRule {

    enum MatchType {
        OPERATION,
        URL
    }

    private final int index;
    private final String descriptor;
    private final MatchType matchType;
    private final String pattern;
    private final Set<Integer> allowedStatusCodes;

    ErrorIgnoreRule(final int index, final String descriptor, final MatchType matchType, final String pattern,
            final Set<Integer> allowedStatusCodes) {
        this.index = index;
        this.descriptor = descriptor;
        this.matchType = matchType;
        this.pattern = pattern;
        this.allowedStatusCodes = allowedStatusCodes == null
                ? Collections.<Integer>emptySet()
                : Collections.unmodifiableSet(new HashSet<Integer>(allowedStatusCodes));
    }

    int getIndex() {
        return index;
    }

    String getDescriptor() {
        return descriptor;
    }

    MatchType getMatchType() {
        return matchType;
    }

    String getPattern() {
        return pattern;
    }

    boolean allowsStatus(final int statusCode) {
        return allowedStatusCodes.contains(statusCode);
    }
}
