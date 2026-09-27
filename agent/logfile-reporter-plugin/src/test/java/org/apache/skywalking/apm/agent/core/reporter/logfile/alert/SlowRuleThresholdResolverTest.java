package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;
import org.junit.Assert;
import org.junit.Test;

/**
 * {@link SlowRuleThresholdResolver} 只读复用 slow_rules 的单测。
 * <p>经配置驱动（{@code fromConfig}）验证：命中规则取规则阈值、未命中回退默认、空规则等价默认；
 * 覆盖后恢复配置，避免污染其它测试。</p>
 */
public class SlowRuleThresholdResolverTest {

    private static final long DEFAULT = 3000L;

    @Test
    public void usesConfiguredRuleThresholdAndFallsBackToDefault() {
        final String prevSlow = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.SLOW_RULES;
        final String prevIgnore = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ERROR_IGNORE_RULES;
        try {
            LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.SLOW_RULES = "operation:/status/400=8000";
            LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ERROR_IGNORE_RULES = "";

            final SlowRuleThresholdResolver resolver = SlowRuleThresholdResolver.fromConfig();
            Assert.assertEquals("命中规则取规则阈值", 8000L, resolver.thresholdMs("/status/400", null, DEFAULT));
            Assert.assertEquals("未命中回退默认", DEFAULT, resolver.thresholdMs("/other", null, DEFAULT));
        } finally {
            LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.SLOW_RULES = prevSlow;
            LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ERROR_IGNORE_RULES = prevIgnore;
        }
    }

    @Test
    public void emptyRulesAlwaysDefault() {
        final String prevSlow = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.SLOW_RULES;
        try {
            LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.SLOW_RULES = "";
            final SlowRuleThresholdResolver resolver = SlowRuleThresholdResolver.fromConfig();
            Assert.assertEquals(DEFAULT, resolver.thresholdMs("/anything", null, DEFAULT));
        } finally {
            LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.SLOW_RULES = prevSlow;
        }
    }
}
