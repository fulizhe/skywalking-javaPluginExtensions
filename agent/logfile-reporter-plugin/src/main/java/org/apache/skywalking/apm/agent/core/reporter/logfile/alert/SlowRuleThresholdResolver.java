package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;
import org.apache.skywalking.apm.agent.core.reporter.logfile.metrics.SlowThresholdResolver;

/**
 * 只读慢阈值解析器：包装 {@link RulesEngine#matchSlow}（纯函数），供 Trace 指标复用告警的 {@code slow_rules}。
 * <p>
 * <b>只读</b>：不调用 {@code TraceAlertMetrics.recordRuleHit}、不并入 {@link TraceEvaluator}、不改告警包行为，
 * 因此不会造成规则命中计数双算。{@code slow_rules} 为空时恒返回默认阈值（与旧行为等价）。
 * </p>
 */
public final class SlowRuleThresholdResolver implements SlowThresholdResolver {

    private final RulesEngine rulesEngine;

    private SlowRuleThresholdResolver(final RulesEngine rulesEngine) {
        this.rulesEngine = rulesEngine;
    }

    /** 用当前告警配置（{@code slow_rules} / {@code error_ignore_rules}）构建只读解析器。 */
    public static SlowRuleThresholdResolver fromConfig() {
        return new SlowRuleThresholdResolver(RulesEngine.fromConfig(
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.SLOW_RULES,
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ERROR_IGNORE_RULES));
    }

    @Override
    public long thresholdMs(final String operation, final String url, final long defaultThresholdMs) {
        return rulesEngine.matchSlow(operation, url, defaultThresholdMs).getThresholdMs();
    }
}
