package org.apache.skywalking.apm.toolkit;

import java.util.Collections;
import java.util.Map;

/**
 * Copy this class into the business application if you want to call these
 * methods directly. The agent plugin intercepts them at runtime.
 */
public class SWHttpClientCollectUtils222222222222 {
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
