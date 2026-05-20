package org.apache.skywalking.apm.plugin.hutool.v5.http;

import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.apache.skywalking.apm.plugin.override.httpclient.v4.OverrideHttpClientPluginConfig;

final class HutoolHttpCollectionSwitch {
    private HutoolHttpCollectionSwitch() {
    }

    static boolean isOfficialCollectEnabled() {
        return HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS;
    }

    static boolean isOverrideCollectEnabled() {
        return OverrideHttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS;
    }
}
