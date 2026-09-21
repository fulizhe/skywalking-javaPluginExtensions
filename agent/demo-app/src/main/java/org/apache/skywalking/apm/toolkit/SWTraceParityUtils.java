package org.apache.skywalking.apm.toolkit;

import java.util.Collections;
import java.util.Map;

/**
 * 宿主工具类桩(FQCN 契约):插件按类名增强本类静态方法。
 * 无 agent 时,方法体为桩实现(空数据);挂载 agent 后,由插件拦截器接管。
 * <p>
 * 设计参照 {@code SWLogfileReporterUtils} 的宿主工具类模式。
 * </p>
 */
public class SWTraceParityUtils {

    /**
     * 影子对账快照:对账计数、差异数、H2 存储状态。
     * 无 agent 时返回空 Map;挂载 agent 后由 {@code TraceParityStatusExposeInterceptor} 接管。
     */
    public static Map<String, Object> statisticParity() {
        return Collections.emptyMap();
    }
}
