package org.apache.skywalking.apm.toolkit;

import java.util.Collections;
import java.util.Map;

/**
 * Copy this class into the business application if you want to call these
 * methods directly. The agent plugin intercepts them at runtime.
 *
 * 与 4.x override 的 SWHttpClientCollectUtils 是不同类名, 避免两个 override
 * 插件争抢同一个被增强类。
 */
public class SWHttpClient5CollectUtils {
    public static Object enableCollect(Map<String, Object> config) {
        return Boolean.FALSE;
    }

    public static Object disableCollect(Map<String, Object> config) {
        return Boolean.FALSE;
    }

    public static Map<String, Object> statisticStatus() {
        return Collections.emptyMap();
    }
}
