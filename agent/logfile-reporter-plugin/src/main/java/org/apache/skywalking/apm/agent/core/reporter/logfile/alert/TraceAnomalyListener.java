package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * 慢/错链路扩展回调。实现类须放在 agent {@code plugins/} 目录下的独立插件 jar 中，
 * 并通过 {@code META-INF/services} 注册；不能仅存在于业务 Spring Boot fat jar。
 */
public interface TraceAnomalyListener {

    /**
     * 异步通知入口，由 {@link AsyncTraceAlertDispatcher} 在独立线程中调用。
     */
    void onTraceAlert(TraceAlertEvent event);

    /**
     * 自定义错误判定，与内置 L1/L2 规则 OR 组合。
     */
    default boolean isError(final TraceSnapshot snapshot) {
        return false;
    }

    /**
     * 自定义慢请求判定；返回 true 时忽略配置阈值。
     */
    default boolean isSlow(final TraceSnapshot snapshot, final long durationMs) {
        return false;
    }
}
