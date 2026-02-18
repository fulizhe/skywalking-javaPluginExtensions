package org.apache.skywalking.apm.agent.core.reporter.logfile;

import org.apache.skywalking.apm.agent.core.boot.PluginConfig;

public class LogFileReporterPluginConfig {

    public static class Plugin {
        @PluginConfig(root = LogFileReporterPluginConfig.class)
        public static class LogFileReporter {

            public static Integer MAX_LOG_SIZE = 1000;

        }

        /** JVM 指标本地缓存相关配置（供 {@code JVMMetricsLocalSender} 使用） */
        @PluginConfig(root = LogFileReporterPluginConfig.class)
        public static class JvmMetricsLocal {

            /** 本地缓存的 JVM 指标条数上限，超过后按 LRU 淘汰，默认 1000 */
            public static Integer MAX_METRICS_DATA_SIZE = 1000;

        }
    }
}