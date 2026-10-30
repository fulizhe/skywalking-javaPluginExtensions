package org.apache.skywalking.apm.plugin.override.httpclient.v5;

import org.apache.skywalking.apm.agent.core.boot.PluginConfig;

public class OverrideHttpClient5PluginConfig {
    public static class Plugin {
        // 命名规则同 4.x override: @PluginConfig 必须标记在真正使用的内部类上。
        // 根类名 OverrideHttpClient5PluginConfig 去掉 PluginConfig 后缀后小写化, 得到配置前缀
        // plugin.overridehttpclient5, 因此 key 为 plugin.overridehttpclient5.collect_http_params。
        @PluginConfig(root = OverrideHttpClient5PluginConfig.class)
        public static class OverrideHttpClient5 {
            /**
             * A dedicated body collection switch for the httpclient5 override plugin.
             * Config key: plugin.overridehttpclient5.collect_http_params
             * 与官方 plugin.httpclient.collect_http_params(query 采集)分离。
             */
            public static boolean COLLECT_HTTP_PARAMS = false;
        }
    }
}
