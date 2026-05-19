package org.apache.skywalking.apm.plugin.override.httpclient.v4;

import org.apache.skywalking.apm.agent.core.boot.PluginConfig;

public class OverrideHttpClientPluginConfig {
    public static class Plugin {
        @PluginConfig(root = OverrideHttpClientPluginConfig.class)
        public static class HttpClient {
            /**
             * A dedicated switch for this override plugin. Keep it separated from
             * SkyWalking official plugin.httpclient.collect_http_params.
             */
            public static boolean COLLECT_HTTP_PARAMS = false;
        }
    }
}
