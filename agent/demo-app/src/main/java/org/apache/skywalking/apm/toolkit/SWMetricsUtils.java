package org.apache.skywalking.apm.toolkit;

import java.util.Collections;
import java.util.Map;

/**
 * 宿主工具类桩(FQCN 契约):插件按类名增强本类静态方法。
 * 无 agent 时,方法体为桩实现(空数据);挂载 agent 后,由插件拦截器接管。
 * <p>
 * 设计参照 {@code SWLogfileReporterUtils} / {@code SWTraceParityUtils} 的宿主工具类模式:
 * 跨 ClassLoader 只返回 JDK 原生类型(Map/List/String/Long/Integer/Boolean)。
 * </p>
 */
public class SWMetricsUtils {

    /**
     * Trace 指标实时快照:当前内存窗口的分钟桶与运行计数。
     * 无 agent 时返回空 Map;挂载 agent 后由 {@code MetricsExposeInterceptor} 接管。
     */
    public static Map<String, Object> statisticMetrics() {
        return Collections.emptyMap();
    }

    /**
     * Trace 指标条件查询。
     * <p>
     * condition 支持:endpoint(含保留键 "*")、fromBucket / toBucket(分钟桶)、
     * resolution(minute|hour,缺省按跨度自动选)、limit(默认 200、上限 1000)。
     * 无 agent 时返回空 Map;挂载 agent 后由拦截器接管。
     */
    public static Map<String, Object> queryMetrics(Map<String, Object> condition) {
        return Collections.emptyMap();
    }

    /**
     * 端点极端值对应的 trace 记录:每端点最大耗时那一次的 traceId 现场(供大屏把"最大耗时"指回链路)。
     * 本期口径=最大耗时(阈值化留待下一步);内存记录、随进程、不落库。
     * 无 agent 时返回空 Map;挂载 agent 后由 {@code MetricsExposeInterceptor} 接管。
     */
    public static Map<String, Object> extremeTraces() {
        return Collections.emptyMap();
    }

    /**
     * 依赖拓扑快照:「入口端点 × 外部依赖」的边列表(补上"我调了哪些依赖、哪条路慢"这一维)。
     * <p>
     * condition 支持:limit(默认 2000)、fromBucket / toBucket(分钟桶,本期边只存内存窗口、
     * 桶范围过滤留待持久化阶段)。边只做<b>段内配对</b>(异步出口不进图)、只到<b>组件类型</b>、
     * 健康标记是<b>边级错误率</b>而非规则引擎告警——读口与页面须写明这三条口径。
     * </p>
     * 无 agent 时返回空 Map;挂载 agent 后由 {@code MetricsExposeInterceptor} 接管。
     */
    public static Map<String, Object> dependencyTopology(Map<String, Object> condition) {
        return Collections.emptyMap();
    }
}
