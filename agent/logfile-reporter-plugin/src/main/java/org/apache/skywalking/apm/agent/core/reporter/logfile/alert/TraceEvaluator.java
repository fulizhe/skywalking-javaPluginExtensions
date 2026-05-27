package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.reporter.logfile.Log;
import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;

/**
 * 合并 trace 的慢/错判定：L1 span.isError、L2 HTTP 5xx、配置规则、SPI 自定义。
 */
public class TraceEvaluator {

    private static final ILog LOGGER = LogManager.getLogger(TraceEvaluator.class);

    private final List<SlowRule> slowRules;
    private final long defaultSlowThresholdMs;
    private final int httpErrorStatusMin;
    private final boolean enableSpanIsError;
    private final boolean enableHttpStatusError;
    private final List<TraceAnomalyListener> listeners;

    public TraceEvaluator(final List<SlowRule> slowRules, final long defaultSlowThresholdMs,
            final int httpErrorStatusMin, final boolean enableSpanIsError, final boolean enableHttpStatusError,
            final List<TraceAnomalyListener> listeners) {
        this.slowRules = slowRules;
        this.defaultSlowThresholdMs = defaultSlowThresholdMs;
        this.httpErrorStatusMin = httpErrorStatusMin;
        this.enableSpanIsError = enableSpanIsError;
        this.enableHttpStatusError = enableHttpStatusError;
        this.listeners = listeners;
    }

    public static TraceEvaluator fromConfig(final List<TraceAnomalyListener> listeners) {
        final long defaultSlow = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.DEFAULT_SLOW_THRESHOLD_MS != null
                && LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.DEFAULT_SLOW_THRESHOLD_MS > 0
                ? LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.DEFAULT_SLOW_THRESHOLD_MS : 3000L;
        final int httpMin = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.HTTP_ERROR_STATUS_MIN != null
                && LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.HTTP_ERROR_STATUS_MIN > 0
                ? LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.HTTP_ERROR_STATUS_MIN : 500;
        final List<SlowRule> slowRules = SlowRuleParser.parse(
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.SLOW_RULES);
        final boolean enableSpanIsError = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLE_SPAN_IS_ERROR == null
                || LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLE_SPAN_IS_ERROR;
        final boolean enableHttpStatusError = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLE_HTTP_STATUS_ERROR == null
                || LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLE_HTTP_STATUS_ERROR;
        final TraceEvaluator evaluator = new TraceEvaluator(slowRules, defaultSlow, httpMin,
                enableSpanIsError, enableHttpStatusError, listeners);
        if (slowRules.isEmpty()) {
            LOGGER.info("### [TraceAlert] slow_rules is empty, all traces use defaultSlowThresholdMs={}", defaultSlow);
        } else {
            LOGGER.info("### [TraceAlert] parsed {} slow_rules, defaultSlowThresholdMs={}", slowRules.size(), defaultSlow);
        }
        TraceAlertBootstrapLog.logEvaluatorReady(evaluator);
        return evaluator;
    }

    long getDefaultSlowThresholdMs() {
        return defaultSlowThresholdMs;
    }

    int getHttpErrorStatusMin() {
        return httpErrorStatusMin;
    }

    boolean isEnableSpanIsError() {
        return enableSpanIsError;
    }

    boolean isEnableHttpStatusError() {
        return enableHttpStatusError;
    }

    int getSlowRuleCount() {
        return slowRules == null ? 0 : slowRules.size();
    }

    public EvaluationResult evaluate(final TraceSnapshot snapshot) {
        final boolean error = hasError(snapshot);
        final Log.SpanInfo entrySpan = TraceSpanUtils.findPrimaryEntrySpan(snapshot);
        final String entryOperation = entrySpan == null ? null : entrySpan.getOperationName();
        final String url = entrySpan == null ? null : TraceSpanUtils.getTagValue(entrySpan, TraceSpanUtils.TAG_URL);
        final long durationMs = TraceSpanUtils.maxDurationMs(snapshot);
        final long thresholdMs = TraceSpanUtils.resolveSlowThresholdMs(slowRules, entryOperation, url,
                defaultSlowThresholdMs);
        final boolean slow = isSlow(snapshot, durationMs, thresholdMs);
        final int errorSpanCount = TraceSpanUtils.countErrorSpans(snapshot);

        final Set<AlertType> alertTypes = EnumSet.noneOf(AlertType.class);
        if (error) {
            alertTypes.add(AlertType.ERROR);
        }
        if (slow) {
            alertTypes.add(AlertType.SLOW);
        }
        return new EvaluationResult(alertTypes, entryOperation, url, durationMs, thresholdMs, errorSpanCount);
    }

    private boolean hasError(final TraceSnapshot snapshot) {
        for (Log log : snapshot.getLogs()) {
            if (log.getSpans() == null) {
                continue;
            }
            for (Log.SpanInfo span : log.getSpans()) {
                if (enableSpanIsError && span.getIsError()) {
                    return true;
                }
                if (enableHttpStatusError && isHttpError(span)) {
                    return true;
                }
            }
        }
        for (TraceAnomalyListener listener : listeners) {
            if (listener.isError(snapshot)) {
                return true;
            }
        }
        return false;
    }

    private boolean isHttpError(final Log.SpanInfo span) {
        final String statusText = TraceSpanUtils.getTagValue(span, TraceSpanUtils.TAG_HTTP_STATUS);
        if (statusText == null || statusText.isEmpty()) {
            return false;
        }
        try {
            return Integer.parseInt(statusText.trim()) >= httpErrorStatusMin;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean isSlow(final TraceSnapshot snapshot, final long durationMs, final long thresholdMs) {
        for (TraceAnomalyListener listener : listeners) {
            if (listener.isSlow(snapshot, durationMs)) {
                return true;
            }
        }
        return durationMs >= thresholdMs;
    }

    public static final class EvaluationResult {
        private final Set<AlertType> alertTypes;
        private final String entryOperation;
        private final String url;
        private final long durationMs;
        private final long thresholdMs;
        private final int errorSpanCount;

        public EvaluationResult(final Set<AlertType> alertTypes, final String entryOperation, final String url,
                final long durationMs, final long thresholdMs, final int errorSpanCount) {
            this.alertTypes = alertTypes;
            this.entryOperation = entryOperation;
            this.url = url;
            this.durationMs = durationMs;
            this.thresholdMs = thresholdMs;
            this.errorSpanCount = errorSpanCount;
        }

        public boolean hasAlert() {
            return alertTypes != null && !alertTypes.isEmpty();
        }

        public Set<AlertType> getAlertTypes() {
            return alertTypes;
        }

        public String getEntryOperation() {
            return entryOperation;
        }

        public String getUrl() {
            return url;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public long getThresholdMs() {
            return thresholdMs;
        }

        public int getErrorSpanCount() {
            return errorSpanCount;
        }
    }
}
