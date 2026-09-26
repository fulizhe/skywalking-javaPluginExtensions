package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

/**
 * "极端值"判定策略（Phase 5 追溯）：决定一次新观测是否替换某 endpoint 已记录的极端 trace。
 * <p>
 * 本期唯一实现 {@link MaxDurationExtremeTraceSelector}（取最大耗时）。本接口是**预留的可配置接入点**：
 * 下一步的"固定阈值 / 相对基线（如 P99 的 N 倍）自适应阈值"——例如某接口出现 800ms 即需关注——
 * 以新实现注入即可，聚合器与读口契约不变。
 * </p>
 */
public interface ExtremeTraceSelector {

    /** 策略名，供读口/大屏标注当前口径（如 {@code max-duration}）。 */
    String name();

    /**
     * 是否需要用本次观测替换现有记录。
     *
     * @param current    现有极端记录；该端点首次观测时为 {@code null}
     * @param durationMs 本次耗时（ms）
     * @param error      本次是否为错误调用
     */
    boolean shouldReplace(ExtremeTrace current, long durationMs, boolean error);
}
