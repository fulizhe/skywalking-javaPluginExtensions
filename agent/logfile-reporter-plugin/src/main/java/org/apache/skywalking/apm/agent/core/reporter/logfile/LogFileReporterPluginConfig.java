package org.apache.skywalking.apm.agent.core.reporter.logfile;

import org.apache.skywalking.apm.agent.core.boot.PluginConfig;

public class LogFileReporterPluginConfig {

    public static class Plugin {
        @PluginConfig(root = LogFileReporterPluginConfig.class)
        public static class LogFile {

            public static String TOPIC_SEGMENT = "skywalking-segments-logfile";

        }
    }
}