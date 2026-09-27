package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

/**
 * 只读慢阈值解析：按 operation / url 给出该请求的慢阈值（毫秒），使 Trace 指标复用告警的 {@code slow_rules}。
 * <p>
 * 约定：实现必须**只读、无副作用**——不得触发规则命中计数、不得并入告警判定；{@code url} 可为 null
 * （此时仅 {@code operation:} 规则参与匹配）。
 * </p>
 */
public interface SlowThresholdResolver {

    /**
     * @param operation          入口 span 的 operationName
     * @param url                入口 span 的 url（可为 null）
     * @param defaultThresholdMs 未命中任何规则时的默认阈值
     * @return 该请求适用的慢阈值（毫秒）
     */
    long thresholdMs(String operation, String url, long defaultThresholdMs);
}
