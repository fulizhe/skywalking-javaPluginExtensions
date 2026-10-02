package org.apache.skywalking.apm.toolkit;

import java.util.Collections;
import java.util.Map;

/**
 * 宿主工具类桩（FQCN 契约）：插件按类名增强本类静态方法。
 * 无 agent 时，方法体为桩实现（空数据）；挂载 agent 后，由插件拦截器接管。
 * <p>
 * 主题是<b>监控自身</b>而不是被监控的业务：DataCarrier 缓冲区丢弃数、消费线程单批耗时、
 * 未提交 segment 积压、H2 行数与内存估算、环形载荷文件的写指针与压缩率。
 * </p>
 */
public class SWSelfStatUtils {

    /**
     * 自身状态快照：采集入口 / 消费线程 / 存储侧 / JVM 堆 四段。
     * 无 agent 时返回空 Map；挂载 agent 后由 {@code SelfStatExposeInterceptor} 接管。
     */
    public static Map<String, Object> statisticSelf() {
        return Collections.emptyMap();
    }
}
