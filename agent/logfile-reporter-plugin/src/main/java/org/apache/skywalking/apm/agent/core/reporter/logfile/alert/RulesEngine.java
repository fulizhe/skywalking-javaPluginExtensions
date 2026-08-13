package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;

/**
 * SLOW 与 ERROR 白名单统一匹配入口：启动期 build，运行时只读。
 * <p>
 * 告警规则语义的唯一驻点：匹配维度、规则类型、绑定元数据、状态码白名单与编译规则
 * 均以静态嵌套类型收敛于此；外部仅见 {@code fromConfig} 与匹配/统计读口。
 * </p>
 */
final class RulesEngine {

    private static final ILog LOGGER = LogManager.getLogger(RulesEngine.class);

    private final AntPatternCache patternCache;
    private final List<SlowCompiledRule> slowRules;
    private final List<ErrorIgnoreCompiledRule> errorIgnoreRules;
    private final List<AlertRuleBinding> ruleBindings;

    RulesEngine(final List<SlowCompiledRule> slowRules, final List<ErrorIgnoreCompiledRule> errorIgnoreRules,
            final List<AlertRuleBinding> ruleBindings, final AntPatternCache patternCache) {
        this.slowRules = safeCopy(slowRules);
        this.errorIgnoreRules = safeCopy(errorIgnoreRules);
        this.ruleBindings = safeCopy(ruleBindings);
        this.patternCache = patternCache == null ? new AntPatternCache() : patternCache;
    }

    RulesEngine(final List<SlowCompiledRule> slowRules, final List<ErrorIgnoreCompiledRule> errorIgnoreRules) {
        this(slowRules, errorIgnoreRules, new ArrayList<AlertRuleBinding>(), new AntPatternCache());
    }

    static RulesEngine fromConfig(final String slowRulesRaw, final String errorIgnoreRulesRaw) {
        return RulesAggregateParser.parse(slowRulesRaw, errorIgnoreRulesRaw);
    }

    int getSlowRuleCount() {
        return slowRules.size();
    }

    int getErrorIgnoreRuleCount() {
        return errorIgnoreRules.size();
    }

    int getTotalRuleCount() {
        return ruleBindings.size();
    }

    List<AlertRuleBinding> getRuleBindings() {
        return ruleBindings;
    }

    List<ErrorIgnoreCompiledRule> getErrorIgnoreRules() {
        return errorIgnoreRules;
    }

    /**
     * 当前已编译去重后的 Ant pattern 条数（= 缓存 size），供状态快照与启动摘要读取。
     */
    int getCompiledPatternCount() {
        return patternCache.size();
    }

    /**
     * 解析慢阈值：operation 规则优先于 url；同类型后配覆盖前配；均未命中则默认阈值。
     */
    SlowMatchResult matchSlow(final String operation, final String url, final long defaultThresholdMs) {
        Long operationThreshold = null;
        int operationRuleIndex = -1;
        Long urlThreshold = null;
        int urlRuleIndex = -1;
        for (SlowCompiledRule rule : slowRules) {
            if (!rule.matches(operation, url)) {
                continue;
            }
            if (rule.getMatchKind() == MatchKind.OPERATION) {
                operationThreshold = rule.getThresholdMs();
                operationRuleIndex = rule.getIndex();
            } else {
                urlThreshold = rule.getThresholdMs();
                urlRuleIndex = rule.getIndex();
            }
        }
        if (operationThreshold != null) {
            return new SlowMatchResult(operationThreshold, operationRuleIndex);
        }
        if (urlThreshold != null) {
            return new SlowMatchResult(urlThreshold, urlRuleIndex);
        }
        return new SlowMatchResult(defaultThresholdMs, -1);
    }

    /**
     * @return 命中规则全局下标（slow_rules 在前、error_ignore_rules 在后），未命中返回 -1
     */
    int matchErrorIgnoreRuleIndex(final String operation, final String url, final int statusCode) {
        for (ErrorIgnoreCompiledRule rule : errorIgnoreRules) {
            if (rule.getMatchKind() == MatchKind.OPERATION && rule.matches(operation, url)
                    && rule.allowsStatus(statusCode)) {
                return rule.getIndex();
            }
        }
        for (ErrorIgnoreCompiledRule rule : errorIgnoreRules) {
            if (rule.getMatchKind() == MatchKind.URL && rule.matches(operation, url)
                    && rule.allowsStatus(statusCode)) {
                return rule.getIndex();
            }
        }
        return -1;
    }

    void logStartupSummary() {
        LOGGER.info("### [TraceAlert] RulesEngine ready: slowRules={}, errorIgnoreRules={}, totalRules={}, "
                        + "antPatternCacheSize={}",
                slowRules.size(), errorIgnoreRules.size(), ruleBindings.size(), patternCache.size());
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> safeCopy(final List<? extends T> list) {
        return list == null ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(list));
    }

    static final class SlowMatchResult {
        private final long thresholdMs;
        private final int ruleIndex;

        SlowMatchResult(final long thresholdMs, final int ruleIndex) {
            this.thresholdMs = thresholdMs;
            this.ruleIndex = ruleIndex;
        }

        long getThresholdMs() {
            return thresholdMs;
        }

        int getRuleIndex() {
            return ruleIndex;
        }
    }

    // ============================ 嵌套值类型 ============================

    /**
     * 规则匹配维度：operation 整串 或 url（path / 全串视语法而定）。
     */
    enum MatchKind {
        OPERATION,
        URL
    }

    /**
     * 告警规则类型，用于 metrics {@code rules[].type}。
     */
    enum AlertRuleType {
        SLOW,
        ERROR_IGNORE
    }

    /**
     * 单条规则的 metrics 绑定元数据（启动期固定，供 {@link TraceAlertMetrics} 快照）。
     */
    static final class AlertRuleBinding {

        private final int ruleIndex;
        private final String descriptor;
        private final AlertRuleType type;

        AlertRuleBinding(final int ruleIndex, final String descriptor, final AlertRuleType type) {
            this.ruleIndex = ruleIndex;
            this.descriptor = descriptor;
            this.type = type;
        }

        int getRuleIndex() {
            return ruleIndex;
        }

        String getDescriptor() {
            return descriptor;
        }

        AlertRuleType getType() {
            return type;
        }
    }

    /**
     * HTTP 状态码白名单，用固定数组避免热路径装箱。
     */
    static final class StatusCodeAllowlist {

        private final boolean[] allowed = new boolean[600];

        StatusCodeAllowlist(final Iterable<Integer> statusCodes) {
            if (statusCodes == null) {
                return;
            }
            for (Integer code : statusCodes) {
                if (code != null && code.intValue() >= 100 && code.intValue() <= 599) {
                    allowed[code.intValue()] = true;
                }
            }
        }

        boolean allows(final int statusCode) {
            return statusCode >= 100 && statusCode <= 599 && allowed[statusCode];
        }
    }

    /**
     * 启动期编译后的单条规则，热路径仅调用 {@link #matches(String, String)}。
     */
    static class CompiledRule {

        private final int index;
        private final String descriptor;
        private final MatchKind matchKind;
        private final CompiledAntPattern matcher;

        CompiledRule(final int index, final String descriptor, final MatchKind matchKind,
                final CompiledAntPattern matcher) {
            this.index = index;
            this.descriptor = descriptor;
            this.matchKind = matchKind;
            this.matcher = matcher;
        }

        int getIndex() {
            return index;
        }

        String getDescriptor() {
            return descriptor;
        }

        MatchKind getMatchKind() {
            return matchKind;
        }

        boolean matches(final String operation, final String url) {
            if (matchKind == MatchKind.OPERATION) {
                return matcher.matches(operation);
            }
            return matcher.matches(UrlPathExtractor.extractPath(url));
        }
    }

    /**
     * 慢请求编译规则，payload 为阈值（毫秒）。
     */
    static final class SlowCompiledRule extends CompiledRule {

        private final long thresholdMs;

        SlowCompiledRule(final int index, final String descriptor, final MatchKind matchKind,
                final CompiledAntPattern matcher, final long thresholdMs) {
            super(index, descriptor, matchKind, matcher);
            this.thresholdMs = thresholdMs;
        }

        long getThresholdMs() {
            return thresholdMs;
        }
    }

    /**
     * 错误白名单编译规则，payload 为允许的状态码集合。
     */
    static final class ErrorIgnoreCompiledRule extends CompiledRule {

        private final StatusCodeAllowlist allowedStatusCodes;

        ErrorIgnoreCompiledRule(final int index, final String descriptor, final MatchKind matchKind,
                final CompiledAntPattern matcher, final StatusCodeAllowlist allowedStatusCodes) {
            super(index, descriptor, matchKind, matcher);
            this.allowedStatusCodes = allowedStatusCodes;
        }

        boolean allowsStatus(final int statusCode) {
            return allowedStatusCodes.allows(statusCode);
        }
    }
}
