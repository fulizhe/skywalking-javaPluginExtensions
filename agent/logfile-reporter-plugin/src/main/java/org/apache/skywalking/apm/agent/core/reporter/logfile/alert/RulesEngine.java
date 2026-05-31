package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;

/**
 * SLOW 与 ERROR 白名单统一匹配入口：启动期 build，运行时只读。
 */
final class RulesEngine {

    private static final ILog LOGGER = LogManager.getLogger(RulesEngine.class);

    private final List<SlowCompiledRule> slowRules;
    private final List<ErrorIgnoreCompiledRule> errorIgnoreRules;
    private final List<AlertRuleBinding> ruleBindings;

    RulesEngine(final List<SlowCompiledRule> slowRules, final List<ErrorIgnoreCompiledRule> errorIgnoreRules,
            final List<AlertRuleBinding> ruleBindings) {
        this.slowRules = slowRules == null ? Collections.<SlowCompiledRule>emptyList()
                : Collections.unmodifiableList(new ArrayList<SlowCompiledRule>(slowRules));
        this.errorIgnoreRules = errorIgnoreRules == null ? Collections.<ErrorIgnoreCompiledRule>emptyList()
                : Collections.unmodifiableList(new ArrayList<ErrorIgnoreCompiledRule>(errorIgnoreRules));
        this.ruleBindings = ruleBindings == null ? Collections.<AlertRuleBinding>emptyList()
                : Collections.unmodifiableList(new ArrayList<AlertRuleBinding>(ruleBindings));
    }

    RulesEngine(final List<SlowCompiledRule> slowRules, final List<ErrorIgnoreCompiledRule> errorIgnoreRules) {
        this(slowRules, errorIgnoreRules, buildBindings(slowRules, errorIgnoreRules));
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
     * 解析慢阈值：operation 规则优先于 url；同类型后配覆盖前配；均未命中则默认阈值。
     */
    SlowMatchResult matchSlow(final String operation, final String url, final long defaultThresholdMs) {
        Long operationThreshold = null;
        Integer operationRuleIndex = null;
        Long urlThreshold = null;
        Integer urlRuleIndex = null;
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
            return new SlowMatchResult(operationThreshold.longValue(), operationRuleIndex.intValue());
        }
        if (urlThreshold != null) {
            return new SlowMatchResult(urlThreshold.longValue(), urlRuleIndex.intValue());
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
                slowRules.size(), errorIgnoreRules.size(), ruleBindings.size(), AntPatternCache.cacheSize());
    }

    private static List<AlertRuleBinding> buildBindings(final List<SlowCompiledRule> slowRules,
            final List<ErrorIgnoreCompiledRule> errorIgnoreRules) {
        final List<SlowCompiledRule> slow = slowRules == null ? Collections.<SlowCompiledRule>emptyList() : slowRules;
        final List<ErrorIgnoreCompiledRule> error = errorIgnoreRules == null ? Collections.<ErrorIgnoreCompiledRule>emptyList()
                : errorIgnoreRules;
        final List<AlertRuleBinding> bindings = new ArrayList<AlertRuleBinding>();
        for (SlowCompiledRule rule : slow) {
            bindings.add(new AlertRuleBinding(rule.getIndex(), rule.getDescriptor(), AlertRuleType.SLOW));
        }
        for (ErrorIgnoreCompiledRule rule : error) {
            bindings.add(new AlertRuleBinding(rule.getIndex(), rule.getDescriptor(), AlertRuleType.ERROR_IGNORE));
        }
        return bindings;
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
}
