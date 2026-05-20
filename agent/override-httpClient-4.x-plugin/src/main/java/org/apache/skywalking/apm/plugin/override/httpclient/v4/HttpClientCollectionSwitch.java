package org.apache.skywalking.apm.plugin.override.httpclient.v4;

import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;

import java.util.LinkedHashMap;
import java.util.Map;

final class HttpClientCollectionSwitch {
    private static final ILog LOGGER = LogManager.getLogger(HttpClientCollectionSwitch.class);

    private HttpClientCollectionSwitch() {
    }

    static boolean isOfficialCollectEnabled() {
        return HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS;
    }

    static boolean isOverrideCollectEnabled() {
        return OverrideHttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS;
    }

    static void toggleRuntimeCollect(final boolean enabled) {
        final boolean previous = OverrideHttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS;
        OverrideHttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS = enabled;
        LOGGER.info(
                "### Override httpclient parameter collection switch updated, previous={}, current={}, official={}, effective={}",
                previous, enabled, isOfficialCollectEnabled(), shouldCollect());
    }

    static boolean shouldCollect() {
        return isOfficialCollectEnabled() && isOverrideCollectEnabled();
    }

    static Map<String, Object> currentStatus() {
        final Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("officialCollectHttpParams", isOfficialCollectEnabled());
        result.put("overrideCollectHttpParams", isOverrideCollectEnabled());
        result.put("effectiveCollectHttpParams", shouldCollect());
        result.put("httpParamsLengthThreshold",
                HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD);
        return result;
    }
}
