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
                "### Override httpclient body collection switch updated, previous={}, current={}, officialQuery={}, effectiveQuery={}, effectiveBody={}",
                previous, enabled, isOfficialCollectEnabled(), shouldCollectQuery(), shouldCollectBody());
    }

    static boolean shouldCollect() {
        return shouldCollectQuery() || shouldCollectBody();
    }

    static boolean shouldCollectQuery() {
        return isOfficialCollectEnabled();
    }

    static boolean shouldCollectBody() {
        return isOverrideCollectEnabled();
    }

    static Map<String, Object> currentStatus() {
        final Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("officialCollectHttpParams", isOfficialCollectEnabled());
        result.put("overrideCollectHttpParams", isOverrideCollectEnabled());
        result.put("effectiveCollectQueryParams", shouldCollectQuery());
        result.put("effectiveCollectBodyParams", shouldCollectBody());
        result.put("effectiveCollectHttpParams", shouldCollect());
        result.put("httpParamsLengthThreshold",
                HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD);
        return result;
    }
}
