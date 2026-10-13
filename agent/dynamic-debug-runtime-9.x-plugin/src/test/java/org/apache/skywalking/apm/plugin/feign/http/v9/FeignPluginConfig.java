/*
 * 测试替身: 镜像 skywalking 官方插件配置类的 FQN/字段名, 使反射改写可在纯单测中断言。
 * 真实类位于 apm-feign-default-http-9.x-plugin (org.apache.skywalking.apm.plugin.feign.http.v9.FeignPluginConfig)。
 */
package org.apache.skywalking.apm.plugin.feign.http.v9;

public class FeignPluginConfig {
    public static class Plugin {
        public static class Feign {
            public static boolean COLLECT_REQUEST_BODY = false;
        }
    }
}
