/*
 * 测试替身: 镜像 skywalking 官方插件配置类的 FQN/字段名, 使反射改写可在纯单测中断言。
 * 真实类位于 apm-jdbc-commons (org.apache.skywalking.apm.plugin.jdbc.JDBCPluginConfig)。
 */
package org.apache.skywalking.apm.plugin.jdbc;

public class JDBCPluginConfig {
    public static class Plugin {
        public static class JDBC {
            public static boolean TRACE_SQL_PARAMETERS = false;
        }
    }
}
