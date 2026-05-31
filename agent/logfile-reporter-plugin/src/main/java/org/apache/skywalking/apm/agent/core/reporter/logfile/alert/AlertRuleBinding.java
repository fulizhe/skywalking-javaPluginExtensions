package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * 单条规则的 metrics 绑定元数据（启动期固定，供 {@link TraceAlertMetrics} 快照）。
 */
final class AlertRuleBinding {

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
