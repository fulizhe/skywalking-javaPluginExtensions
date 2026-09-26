package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

/**
 * 默认极端值策略：每端点仅保留**最大耗时**那一条（首次必记，严格更大才替换；并列保留先到者）。
 * <p>与当前大屏"最大耗时"列口径一致，不引入阈值（阈值化留待 {@link ExtremeTraceSelector} 的后续实现）。</p>
 */
public final class MaxDurationExtremeTraceSelector implements ExtremeTraceSelector {

    @Override
    public String name() {
        return "max-duration";
    }

    @Override
    public boolean shouldReplace(final ExtremeTrace current, final long durationMs, final boolean error) {
        return current == null || durationMs > current.getDurationMs();
    }
}
