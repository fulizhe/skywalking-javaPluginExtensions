package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.List;

import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;

/**
 * 慢/错链路告警模块启动期日志：汇总最终生效配置，便于排查配置未生效、webhook 地址错误等问题。
 */
final class TraceAlertBootstrapLog {

    private static final ILog LOGGER = LogManager.getLogger(TraceAlertBootstrapLog.class);

    private TraceAlertBootstrapLog() {
    }

    static void logEffectiveAlertConfig() {
        LOGGER.info("### [TraceAlert] effective config: enabled={}, defaultSlowThresholdMs={}, httpErrorStatusMin={}, "
                        + "enableSpanIsError={}, enableHttpStatusError={}, slowRules=[{}], errorIgnoreRules=[{}], "
                        + "listenerClass=[{}], notifiedCacheTtlMs={}",
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLED,
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.DEFAULT_SLOW_THRESHOLD_MS,
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.HTTP_ERROR_STATUS_MIN,
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLE_SPAN_IS_ERROR,
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLE_HTTP_STATUS_ERROR,
                abbreviate(LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.SLOW_RULES, 256),
                abbreviate(LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ERROR_IGNORE_RULES, 256),
                orDash(LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.LISTENER_CLASS),
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.NOTIFIED_CACHE_TTL_MS);

        final String urlTemplate = WebhookUrlResolver.getWebhookUrlTemplate();
        final String resolvedUrl = WebhookUrlResolver.resolveConfiguredUrl();
        LOGGER.info("### [TraceAlert] webhook: urlTemplate=[{}], webhookPath=[{}], host=[{}], portEnv=[{}], "
                        + "portDefault={}, connectTimeoutMs={}, readTimeoutMs={}, resolvedUrl=[{}]",
                orDash(LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_URL),
                orDash(LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PATH),
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_HOST,
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PORT_ENV,
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PORT_DEFAULT,
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_CONNECT_TIMEOUT_MS,
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_READ_TIMEOUT_MS,
                orDash(resolvedUrl));

        if (urlTemplate != null && urlTemplate.indexOf("${") >= 0 && resolvedUrl != null
                && resolvedUrl.equals(urlTemplate)) {
            LOGGER.warn("### [TraceAlert] webhook template still contains unresolved placeholders, "
                            + "check env var [{}] or default port.",
                    LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PORT_ENV);
        }
        if (urlTemplate == null || urlTemplate.trim().isEmpty()) {
            LOGGER.warn("### [TraceAlert] webhook_url and webhook_path are both empty, "
                    + "HTTP callback will not be registered unless SPI/custom listener is configured.");
        }
    }

    static void logListeners(final List<TraceAnomalyListener> listeners) {
        if (listeners == null || listeners.isEmpty()) {
            LOGGER.warn("### [TraceAlert] no TraceAnomalyListener loaded, alerts will be evaluated but not delivered.");
            return;
        }
        int active = 0;
        final StringBuilder names = new StringBuilder();
        for (TraceAnomalyListener listener : listeners) {
            if (listener instanceof NoOpTraceAnomalyListener) {
                continue;
            }
            active++;
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(listener.getClass().getName());
        }
        if (active == 0) {
            LOGGER.warn("### [TraceAlert] only NoOpTraceAnomalyListener is active, no outbound notification.");
        } else {
            LOGGER.info("### [TraceAlert] active listeners ({}): {}", active, names);
        }
    }

    static void logEvaluatorReady(final TraceEvaluator evaluator) {
        LOGGER.info("### [TraceAlert] TraceEvaluator ready: defaultSlowThresholdMs={}, httpErrorStatusMin={}, "
                        + "enableSpanIsError={}, enableHttpStatusError={}, slowRuleCount={}, errorIgnoreRuleCount={}",
                evaluator.getDefaultSlowThresholdMs(),
                evaluator.getHttpErrorStatusMin(),
                evaluator.isEnableSpanIsError(),
                evaluator.isEnableHttpStatusError(),
                evaluator.getSlowRuleCount(),
                evaluator.getErrorIgnoreRuleCount());
    }

    private static String orDash(final String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }

    private static String abbreviate(final String value, final int maxLen) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        final String trimmed = value.trim();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen) + "...(truncated)";
    }
}
