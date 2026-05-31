package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * 告警规则类型，用于 metrics {@code rules[].type}。
 */
enum AlertRuleType {
    SLOW,
    ERROR_IGNORE
}
