package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;

/**
 * 慢/错链路告警运行指标（进程内累计），供 {@code SWLogfileReporterUtils#statisticStatus()} 等对外暴露。
 */
public final class TraceAlertMetrics {

    private static final TraceAlertMetrics INSTANCE = new TraceAlertMetrics();

    private final AtomicBoolean dispatcherInitialized = new AtomicBoolean(false);

    private final AtomicLong dispatchSubmitted = new AtomicLong();
    private final AtomicLong dispatchSkippedDuplicate = new AtomicLong();
    private final AtomicLong listenerInvocationFailed = new AtomicLong();

    private final AtomicLong httpTotalAttempts = new AtomicLong();
    private final AtomicLong httpSuccessCount = new AtomicLong();
    private final AtomicLong httpFailureCount = new AtomicLong();
    private final AtomicLong httpSkippedEmptyUrl = new AtomicLong();

    private volatile long lastSuccessTimeMs;
    private volatile long lastFailureTimeMs;
    private volatile int lastHttpStatus;
    private volatile String lastFailureReason;
    private volatile String lastTargetUrl;
    private volatile String lastTraceId;

    private TraceAlertMetrics() {
    }

    public static TraceAlertMetrics get() {
        return INSTANCE;
    }

    public void markDispatcherInitialized() {
        dispatcherInitialized.set(true);
    }

    public void recordDispatchSubmitted() {
        dispatchSubmitted.incrementAndGet();
    }

    public void recordDispatchSkippedDuplicate() {
        dispatchSkippedDuplicate.incrementAndGet();
    }

    public void recordListenerInvocationFailed() {
        listenerInvocationFailed.incrementAndGet();
    }

    public void recordHttpAttempt() {
        httpTotalAttempts.incrementAndGet();
    }

    public void recordHttpSkippedEmptyUrl() {
        httpSkippedEmptyUrl.incrementAndGet();
    }

    public void recordHttpSuccess(final String traceId, final String targetUrl) {
        httpSuccessCount.incrementAndGet();
        lastSuccessTimeMs = System.currentTimeMillis();
        lastTraceId = traceId;
        lastTargetUrl = targetUrl;
    }

    public void recordHttpFailure(final String traceId, final String targetUrl, final int httpStatus,
            final String reason) {
        httpFailureCount.incrementAndGet();
        lastFailureTimeMs = System.currentTimeMillis();
        lastTraceId = traceId;
        lastTargetUrl = targetUrl;
        lastHttpStatus = httpStatus;
        lastFailureReason = abbreviate(reason, 512);
    }

    /**
     * 返回指标与配置快照，供业务 JSON 序列化。
     */
    public Map<String, Object> snapshot() {
        final Map<String, Object> root = new HashMap<String, Object>();
        root.put("config", buildConfigSnapshot());
        root.put("dispatcher", buildDispatcherSnapshot());
        root.put("httpWebhook", buildHttpWebhookSnapshot());
        return root;
    }

    public static Map<String, Object> buildConfigSnapshot() {
        final Map<String, Object> config = new HashMap<String, Object>();
        config.put("enabled", LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLED);
        config.put("defaultSlowThresholdMs",
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.DEFAULT_SLOW_THRESHOLD_MS);
        config.put("httpErrorStatusMin",
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.HTTP_ERROR_STATUS_MIN);
        config.put("enableSpanIsError",
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLE_SPAN_IS_ERROR);
        config.put("enableHttpStatusError",
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLE_HTTP_STATUS_ERROR);
        config.put("slowRules", LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.SLOW_RULES);
        config.put("listenerClass", LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.LISTENER_CLASS);
        config.put("webhookUrl", LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_URL);
        config.put("webhookPath", LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PATH);
        config.put("webhookHost", LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_HOST);
        config.put("webhookPortEnv", LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PORT_ENV);
        config.put("webhookPortDefault",
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PORT_DEFAULT);
        config.put("webhookConnectTimeoutMs",
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_CONNECT_TIMEOUT_MS);
        config.put("webhookReadTimeoutMs",
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_READ_TIMEOUT_MS);

        final String urlTemplate = WebhookUrlResolver.getWebhookUrlTemplate();
        final String resolvedUrl = WebhookUrlResolver.resolveConfiguredUrl();
        config.put("webhookUrlTemplate", urlTemplate);
        config.put("webhookResolvedUrl", resolvedUrl);
        config.put("webhookPlaceholderUnresolved", Boolean.valueOf(isPlaceholderUnresolved(urlTemplate, resolvedUrl)));
        return config;
    }

    private Map<String, Object> buildDispatcherSnapshot() {
        final Map<String, Object> dispatcher = new HashMap<String, Object>();
        final Boolean enabled = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLED;
        dispatcher.put("enabled", enabled);
        dispatcher.put("initialized", dispatcherInitialized.get());
        dispatcher.put("dispatchSubmitted", dispatchSubmitted.get());
        dispatcher.put("dispatchSkippedDuplicate", dispatchSkippedDuplicate.get());
        dispatcher.put("listenerInvocationFailed", listenerInvocationFailed.get());
        return dispatcher;
    }

    private Map<String, Object> buildHttpWebhookSnapshot() {
        final Map<String, Object> http = new HashMap<String, Object>();
        final long total = httpTotalAttempts.get();
        final long success = httpSuccessCount.get();
        final long failure = httpFailureCount.get();
        final long skipped = httpSkippedEmptyUrl.get();

        http.put("totalAttempts", total);
        http.put("successCount", success);
        http.put("failureCount", failure);
        http.put("skippedEmptyUrl", skipped);
        http.put("successRatePercent", formatSuccessRate(success, total));
        http.put("lastSuccessTimeMs", lastSuccessTimeMs > 0 ? lastSuccessTimeMs : null);
        http.put("lastFailureTimeMs", lastFailureTimeMs > 0 ? lastFailureTimeMs : null);
        http.put("lastHttpStatus", lastHttpStatus > 0 ? lastHttpStatus : null);
        http.put("lastFailureReason", lastFailureReason);
        http.put("lastTargetUrl", lastTargetUrl);
        http.put("lastTraceId", lastTraceId);
        return http;
    }

    private static boolean isPlaceholderUnresolved(final String urlTemplate, final String resolvedUrl) {
        return urlTemplate != null && urlTemplate.indexOf("${") >= 0 && resolvedUrl != null
                && resolvedUrl.equals(urlTemplate);
    }

    private static String formatSuccessRate(final long success, final long total) {
        if (total <= 0) {
            return "0.00";
        }
        final double rate = success * 100.0D / total;
        return String.format("%.2f", rate);
    }

    /** 单测重置累计值，生产代码勿调用。 */
    static void resetForTest() {
        final TraceAlertMetrics m = INSTANCE;
        m.dispatcherInitialized.set(false);
        m.dispatchSubmitted.set(0);
        m.dispatchSkippedDuplicate.set(0);
        m.listenerInvocationFailed.set(0);
        m.httpTotalAttempts.set(0);
        m.httpSuccessCount.set(0);
        m.httpFailureCount.set(0);
        m.httpSkippedEmptyUrl.set(0);
        m.lastSuccessTimeMs = 0;
        m.lastFailureTimeMs = 0;
        m.lastHttpStatus = 0;
        m.lastFailureReason = null;
        m.lastTargetUrl = null;
        m.lastTraceId = null;
    }

    private static String abbreviate(final String value, final int maxLen) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen);
    }
}
