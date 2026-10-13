/*
 * 测试替身: 镜像 skywalking 官方插件配置类的 FQN/字段名, 使反射改写可在纯单测中断言。
 * 真实类位于 apm-tomcat-10x-plugin (org.apache.skywalking.apm.plugin.tomcat10x.TomcatPluginConfig)。
 */
package org.apache.skywalking.apm.plugin.tomcat10x;

public class TomcatPluginConfig {
    public static class Plugin {
        public static class Tomcat {
            public static boolean COLLECT_HTTP_PARAMS = false;
        }
    }
}
