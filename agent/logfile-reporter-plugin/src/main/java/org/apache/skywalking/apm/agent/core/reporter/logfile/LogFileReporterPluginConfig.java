package org.apache.skywalking.apm.agent.core.reporter.logfile;

import org.apache.skywalking.apm.agent.core.boot.PluginConfig;

public class LogFileReporterPluginConfig {

    public static class Plugin {
        @PluginConfig(root = LogFileReporterPluginConfig.class)
        public static class LogFileReporter {

            public static Integer MAX_LOG_SIZE = 1000;

        }
    }
}