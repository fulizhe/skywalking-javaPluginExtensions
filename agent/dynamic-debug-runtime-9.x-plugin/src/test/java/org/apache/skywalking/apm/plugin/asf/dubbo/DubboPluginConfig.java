/*
 * 测试替身: 镜像 skywalking 官方插件配置类的 FQN/字段名, 使反射改写可在纯单测中断言。
 * 真实类位于 apm-dubbo-2.7.x-plugin (org.apache.skywalking.apm.plugin.asf.dubbo.DubboPluginConfig)。
 */
package org.apache.skywalking.apm.plugin.asf.dubbo;

public class DubboPluginConfig {
    public static class Plugin {
        public static class Dubbo {
            public static boolean COLLECT_CONSUMER_ARGUMENTS = false;
            public static boolean COLLECT_PROVIDER_ARGUMENTS = false;
        }
    }
}
