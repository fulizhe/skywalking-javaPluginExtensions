package org.apache.skywalking.apm.plugin.override.httpclient.v4;

import org.apache.skywalking.apm.agent.core.boot.PluginConfig;

public class OverrideHttpClientPluginConfig {
    public static class Plugin {
        // 命名规则: https://skywalking.apache.org/docs/skywalking-java/v9.4.0/en/setup/service-agent/java-agent/java-plugin-development-guide/
        @PluginConfig(root = OverrideHttpClientPluginConfig.class)
        public static class OverrideHttpClient {
            /**
             * A dedicated body collection switch for this override plugin.
             * Config key: plugin.overridehttpclient.collect_http_params
             * Keep it separated from SkyWalking official
             * plugin.httpclient.collect_http_params, which is used for query
             * parameter collection.
             */
            public static boolean COLLECT_HTTP_PARAMS = false;
        }
    }
}
