package org.apache.skywalking.apm.plugin.override.httpclient.v4;

import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;

import java.util.LinkedHashMap;
import java.util.Map;

final class HttpClientCollectionSwitch {
    private static volatile boolean runtimeCollectEnabled = false;

    private HttpClientCollectionSwitch() {
    }

    static boolean isOfficialCollectEnabled() {
        return HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS;
    }

    static boolean isOverrideCollectEnabled() {
        return OverrideHttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS;
    }

    static boolean isRuntimeCollectEnabled() {
        return runtimeCollectEnabled;
    }

    static void toggleRuntimeCollect(final boolean enabled) {
        runtimeCollectEnabled = enabled;
    }

    static boolean shouldCollect() {
        return isOfficialCollectEnabled() || isOverrideCollectEnabled() || runtimeCollectEnabled;
    }

    static Map<String, Object> currentStatus() {
        final Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("officialCollectHttpParams", isOfficialCollectEnabled());
        result.put("overrideCollectHttpParams", isOverrideCollectEnabled());
        result.put("runtimeCollectHttpParams", runtimeCollectEnabled);
        result.put("effectiveCollectHttpParams", shouldCollect());
        result.put("httpParamsLengthThreshold",
            HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD);
        return result;
    }
}
