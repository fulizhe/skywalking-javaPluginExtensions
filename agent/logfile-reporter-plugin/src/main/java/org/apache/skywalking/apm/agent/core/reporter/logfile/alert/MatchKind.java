package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * 规则匹配维度：operation 整串 或 url（path / 全串视语法而定）。
 */
enum MatchKind {
    OPERATION,
    URL
}
