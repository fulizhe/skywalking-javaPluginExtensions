package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.reporter.logfile.Log;
import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;

/**
 * 合并 trace 的慢/错判定：L1 span.isError、L2 HTTP 5xx、error_ignore_rules 白名单、SPI 自定义。
 */
class TraceEvaluator {

    private static final ILog LOGGER = LogManager.getLogger(TraceEvaluator.class);

    private final RulesEngine rulesEngine;
    private final long defaultSlowThresholdMs;
    private final int httpErrorStatusMin;
    private final boolean enableSpanIsError;
    private final boolean enableHttpStatusError;
    private final List<TraceAnomalyListener> listeners;

    TraceEvaluator(final RulesEngine rulesEngine, final long defaultSlowThresholdMs, final int httpErrorStatusMin,
            final boolean enableSpanIsError, final boolean enableHttpStatusError,
            final List<TraceAnomalyListener> listeners) {
        this.rulesEngine = rulesEngine == null ? new RulesEngine(null, null) : rulesEngine;
        this.defaultSlowThresholdMs = defaultSlowThresholdMs;
        this.httpErrorStatusMin = httpErrorStatusMin;
        this.enableSpanIsError = enableSpanIsError;
        this.enableHttpStatusError = enableHttpStatusError;
        this.listeners = listeners;
    }

    static TraceEvaluator fromConfig(final List<TraceAnomalyListener> listeners) {
        final long defaultSlow = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.DEFAULT_SLOW_THRESHOLD_MS != null
                && LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.DEFAULT_SLOW_THRESHOLD_MS > 0
                ? LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.DEFAULT_SLOW_THRESHOLD_MS : 3000L;
        final int httpMin = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.HTTP_ERROR_STATUS_MIN != null
                && LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.HTTP_ERROR_STATUS_MIN > 0
                ? LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.HTTP_ERROR_STATUS_MIN : 500;
        final RulesEngine rulesEngine = RulesEngine.fromConfig(
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.SLOW_RULES,
                LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ERROR_IGNORE_RULES);
        final boolean enableSpanIsError = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLE_SPAN_IS_ERROR == null
                || LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLE_SPAN_IS_ERROR;
        final boolean enableHttpStatusError = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLE_HTTP_STATUS_ERROR == null
                || LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLE_HTTP_STATUS_ERROR;
        TraceAlertMetrics.get().bindRules(rulesEngine);
        final TraceEvaluator evaluator = new TraceEvaluator(rulesEngine, defaultSlow, httpMin, enableSpanIsError,
                enableHttpStatusError, listeners);
        if (rulesEngine.getSlowRuleCount() == 0) {
            LOGGER.info("### [TraceAlert] slow_rules is empty, all traces use defaultSlowThresholdMs={}", defaultSlow);
        } else {
            LOGGER.info("### [TraceAlert] parsed {} slow_rules, defaultSlowThresholdMs={}",
                    rulesEngine.getSlowRuleCount(), defaultSlow);
        }
        if (rulesEngine.getErrorIgnoreRuleCount() == 0) {
            LOGGER.info("### [TraceAlert] error_ignore_rules is empty");
        } else {
            LOGGER.info("### [TraceAlert] parsed {} error_ignore_rules", rulesEngine.getErrorIgnoreRuleCount());
        }
        rulesEngine.logStartupSummary();
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
        return rulesEngine.getSlowRuleCount();
    }

    int getErrorIgnoreRuleCount() {
        return rulesEngine.getErrorIgnoreRuleCount();
    }

    EvaluationResult evaluate(final TraceSnapshot snapshot) {
        final boolean error = hasError(snapshot);
        final Log.SpanInfo entrySpan = TraceSpanUtils.findPrimaryEntrySpan(snapshot);
        final String entryOperation = entrySpan == null ? null : entrySpan.getOperationName();
        final String url = entrySpan == null ? null : TraceSpanUtils.getTagValue(entrySpan, TraceSpanUtils.TAG_URL);
        final long durationMs = TraceSpanUtils.maxDurationMs(snapshot);
        final RulesEngine.SlowMatchResult slowMatch = rulesEngine.matchSlow(entryOperation, url,
                defaultSlowThresholdMs);
        final long thresholdMs = slowMatch.getThresholdMs();
        if (slowMatch.getRuleIndex() >= 0) {
            TraceAlertMetrics.get().recordRuleHit(slowMatch.getRuleIndex());
        }
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
                if (spanContributesToError(span)) {
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

    private boolean spanContributesToError(final Log.SpanInfo span) {
        final boolean candidate = (enableSpanIsError && span.getIsError())
                || (enableHttpStatusError && isHttpError(span));
        if (!candidate) {
            return false;
        }
        return !isIgnoredHttpSpan(span);
    }

    private boolean isIgnoredHttpSpan(final Log.SpanInfo span) {
        if (rulesEngine.getErrorIgnoreRuleCount() == 0) {
            return false;
        }
        final Integer status = parseHttpStatus(span);
        if (status == null) {
            return false;
        }
        final String operation = span.getOperationName();
        final String url = TraceSpanUtils.getTagValue(span, TraceSpanUtils.TAG_URL);
        final int ruleIndex = rulesEngine.matchErrorIgnoreRuleIndex(operation, url, status.intValue());
        if (ruleIndex < 0) {
            return false;
        }
        TraceAlertMetrics.get().recordRuleHit(ruleIndex);
        return true;
    }

    private Integer parseHttpStatus(final Log.SpanInfo span) {
        final String statusText = TraceSpanUtils.getTagValue(span, TraceSpanUtils.TAG_HTTP_STATUS);
        if (statusText == null || statusText.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(statusText.trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isHttpError(final Log.SpanInfo span) {
        final Integer status = parseHttpStatus(span);
        return status != null && status.intValue() >= httpErrorStatusMin;
    }

    private boolean isSlow(final TraceSnapshot snapshot, final long durationMs, final long thresholdMs) {
        for (TraceAnomalyListener listener : listeners) {
            if (listener.isSlow(snapshot, durationMs)) {
                return true;
            }
        }
        return durationMs >= thresholdMs;
    }

    static final class EvaluationResult {
        private final Set<AlertType> alertTypes;
        private final String entryOperation;
        private final String url;
        private final long durationMs;
        private final long thresholdMs;
        private final int errorSpanCount;

        EvaluationResult(final Set<AlertType> alertTypes, final String entryOperation, final String url,
                final long durationMs, final long thresholdMs, final int errorSpanCount) {
            this.alertTypes = alertTypes;
            this.entryOperation = entryOperation;
            this.url = url;
            this.durationMs = durationMs;
            this.thresholdMs = thresholdMs;
            this.errorSpanCount = errorSpanCount;
        }

        boolean hasAlert() {
            return alertTypes != null && !alertTypes.isEmpty();
        }

        Set<AlertType> getAlertTypes() {
            return alertTypes;
        }

        String getEntryOperation() {
            return entryOperation;
        }

        String getUrl() {
            return url;
        }

        long getDurationMs() {
            return durationMs;
        }

        long getThresholdMs() {
            return thresholdMs;
        }

        int getErrorSpanCount() {
            return errorSpanCount;
        }
    }
}
