/*
 * 测试替身: 镜像 skywalking 官方插件配置类的 FQN/字段名, 使反射改写可在纯单测中断言。
 * 真实类位于 apm-httpclient-commons (org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig)。
 */
package org.apache.skywalking.apm.plugin.httpclient;

public class HttpClientPluginConfig {
    public static class Plugin {
        public static class HttpClient {
            public static boolean COLLECT_HTTP_PARAMS = false;
        }
    }
}
