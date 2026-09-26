package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 端点"极端值"对应的一次调用现场（Phase 5 追溯；内存、仅供参考与回溯，不落库）。
 * <p>
 * 在指标聚合时按 endpoint 记下最极端那一次的 traceId 与现场，使读口
 * （{@code /inner/sw/metrics/extremes}）能把"指标上看到的极端值"指回具体链路。
 * 后续 error/slow 明细持久化到 H2 后，该 traceId 即成为**闭环追踪链条**的入口。
 * </p>
 * <p>
 * 本期口径 = **每端点保留最大耗时那一条**（见 {@link MaxDurationExtremeTraceSelector}）；
 * "可配置阈值 / 相对基线自适应阈值"（例如某接口出现 800ms 即需关注）留待下一步，
 * 经 {@link ExtremeTraceSelector} 注入新策略即可，不改聚合器与读口契约。
 * </p>
 */
public final class ExtremeTrace {

    private final String endpoint;
    private final String service;
    private final String traceId;
    private final long durationMs;
    private final boolean error;
    private final long startTimeMs;

    public ExtremeTrace(final String endpoint, final String service, final String traceId, final long durationMs,
            final boolean error, final long startTimeMs) {
        this.endpoint = endpoint;
        this.service = service;
        this.traceId = traceId;
        this.durationMs = durationMs;
        this.error = error;
        this.startTimeMs = startTimeMs;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getService() {
        return service;
    }

    public String getTraceId() {
        return traceId;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public boolean isError() {
        return error;
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }

    /** JDK 原生 Map（供宿主工具类/大屏消费，保持 ClassLoader 边界只传原生类型）。 */
    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("endpoint", endpoint);
        map.put("service", service);
        map.put("traceId", traceId);
        map.put("durationMs", durationMs);
        map.put("error", error);
        map.put("startTime", startTimeMs);
        return map;
    }
}
