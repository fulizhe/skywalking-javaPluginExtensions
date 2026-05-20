package org.apache.skywalking.apm.plugin.override.httpclient.v4;

import org.apache.skywalking.apm.agent.core.boot.PluginConfig;

public class OverrideHttpClientPluginConfig {
    public static class Plugin {
        // 命名规则: https://skywalking.apache.org/docs/skywalking-java/v9.4.0/en/setup/service-agent/java-agent/java-plugin-development-guide/
        // 1. 使用 @PluginConfig 注解声明配置类; @PluginConfig 必须标记在真正使用的内部类上, 不能标记在外层类，否则无效。
        // 2. 配置 key 自动生成规则: plugin.插件名.字段名
        // 3. 范例参见: plugin.springmvc.collect_http_params 对应的 SpringMVCPluginConfig
        //        https://github.com/apache/skywalking-java/blob/e0e8b3c8c304735991e057d431910ed1f4a57cdd/apm-sniffer/apm-sdk-plugin/spring-plugins/mvc-annotation-commons/src/main/java/org/apache/skywalking/apm/plugin/spring/mvc/commons/SpringMVCPluginConfig.java
        @PluginConfig(root = OverrideHttpClientPluginConfig.class)
        public static class OverrideHttpClient {
            /**
             * A dedicated body collection switch for this override plugin.
             * Config key: plugin.overridehttpclient.collect_http_params
             * Keep it separated from SkyWalking official "plugin.httpclient.collect_http_params", which is used for query
             * parameter collection.
             */
            public static boolean COLLECT_HTTP_PARAMS = false;
        }
    }
}
