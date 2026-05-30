package org.apache.skywalking.apm.agent.core.reporter.logfile;

import org.apache.skywalking.apm.agent.core.boot.PluginConfig;

public class LogFileReporterPluginConfig {

    public static class Plugin {
        @PluginConfig(root = LogFileReporterPluginConfig.class)
        public static class LogFileReporter {
            // -Dskywalking.plugin.logfilereporter.max_log_size=2000
            public static Integer MAX_LOG_SIZE = 1000;

            /**
             * 慢/错链路筛选与异步扩展通知（供 {@code AsyncTraceAlertDispatcher} 使用）。
             * 配置键示例：{@code plugin.logfilereporter.alert.enabled=true}
             */
            public static class Alert {
            	
            	// -Dskywalking.plugin.logfilereporter.alert.enabled=true
                /** 是否启用慢/错链路告警，默认 false */
                public static Boolean ENABLED = false;

                /** 默认慢请求阈值（毫秒） */
                public static Integer DEFAULT_SLOW_THRESHOLD_MS = 3000;

                /** HTTP 状态码大于等于该值视为错误，默认 500 */
                public static Integer HTTP_ERROR_STATUS_MIN = 500;

                /** 是否启用 span.isError 判定 */
                public static Boolean ENABLE_SPAN_IS_ERROR = true;

                /** 是否启用 http.status_code 判定 */
                public static Boolean ENABLE_HTTP_STATUS_ERROR = true;

                /**
                 * 差异化慢请求规则。示例见 README-trace-alert.md。
                 */
                public static String SLOW_RULES = "";

                /**
                 * 错误告警白名单：匹配 operation/url 且 HTTP 状态码在列表内时不参与 ERROR 判定。
                 * 示例见 README-trace-alert.md（error_ignore_rules）。
                 */
                public static String ERROR_IGNORE_RULES = "";

                /**
                 * HTTP 回调完整地址，支持 {@code ${WebPort:9600}} 占位符。
                 * 示例：{@code http://127.0.0.1:${WebPort:9600}/inner/sw/trace-alert}
                 */
                public static String WEBHOOK_URL = "http://127.0.0.1:${WebPort:9600}/inner/sw/trace-alert";

                /**
                 * 未配置 {@link #WEBHOOK_URL} 时，与 {@link #WEBHOOK_HOST}、{@link #WEBHOOK_PORT_ENV}
                 * 拼装 URL。
                 * 示例：{@code /inner/sw/trace-alert}
                 */
                public static String WEBHOOK_PATH = "";

                /** 拼装 webhook 时的主机，默认 127.0.0.1 */
                public static String WEBHOOK_HOST = "127.0.0.1";

                /** 读取业务端口的系统环境变量名，默认 WebPort */
                public static String WEBHOOK_PORT_ENV = "WebPort";

                /** 环境变量未设置时的默认端口 */
                public static Integer WEBHOOK_PORT_DEFAULT = 9600;

                /** Webhook 连接超时（毫秒） */
                public static Integer WEBHOOK_CONNECT_TIMEOUT_MS = 3000;

                /** Webhook 读取超时（毫秒） */
                public static Integer WEBHOOK_READ_TIMEOUT_MS = 5000;

                /** 可选：全限定类名，作为 TraceAnomalyListener 补充加载 */
                public static String LISTENER_CLASS = "";

                /**
                 * 同一 traceId 告警去重缓存 TTL（毫秒），到期后允许再次通知并回收内存，默认 10 分钟。
                 * 配置键示例：{@code plugin.logfilereporter.alert.notified_cache_ttl_ms=600000}
                 */
                public static Integer NOTIFIED_CACHE_TTL_MS = 600_000;
            }
        }

        /** JVM 指标本地缓存相关配置（供 {@code JVMMetricsLocalSender} 使用） */
        @PluginConfig(root = LogFileReporterPluginConfig.class)
        public static class JvmMetricsLocal {

            /** 本地缓存的 JVM 指标条数上限，超过后按 LRU 淘汰，默认 1000 */
            public static Integer MAX_METRICS_DATA_SIZE = 1000;

        }

        /** Meter 指标本地缓存相关配置（供 {@code MeterLocalSender} 使用） */
        @PluginConfig(root = LogFileReporterPluginConfig.class)
        public static class MeterLocal {

            /** 本地缓存的 Meter 指标条数上限，超过后按 LRU 淘汰，默认 300 */
            public static Integer MAX_METER_DATA_SIZE = 300;

        }

    }
}
