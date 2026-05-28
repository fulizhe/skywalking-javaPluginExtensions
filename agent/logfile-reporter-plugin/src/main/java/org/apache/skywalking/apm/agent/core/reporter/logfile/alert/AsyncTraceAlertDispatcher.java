package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;

/**
 * 异步分发慢/错链路告警，同一 traceId 同类型告警在 TTL 窗口内只通知一次。
 */
public class AsyncTraceAlertDispatcher {

    private static final ILog LOGGER = LogManager.getLogger(AsyncTraceAlertDispatcher.class);

    private final TraceEvaluator evaluator;
    private final List<TraceAnomalyListener> listeners;
    private final ExecutorService executor;
    private final NotifiedFlagsCache notifiedFlags;

    public AsyncTraceAlertDispatcher(final TraceEvaluator evaluator, final List<TraceAnomalyListener> listeners) {
        this(evaluator, listeners, NotifiedFlagsCache.fromConfig());
    }

    AsyncTraceAlertDispatcher(final TraceEvaluator evaluator, final List<TraceAnomalyListener> listeners,
            final NotifiedFlagsCache notifiedFlags) {
        this.evaluator = evaluator;
        this.listeners = listeners;
        this.notifiedFlags = notifiedFlags;
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(final Runnable runnable) {
                final Thread thread = new Thread(runnable,
                        "LogfileTraceAlert-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public static AsyncTraceAlertDispatcher createIfEnabled() {
        LOGGER.info("### [TraceAlert] initializing AsyncTraceAlertDispatcher...");
        TraceAlertBootstrapLog.logEffectiveAlertConfig();

        final Boolean enabled = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.ENABLED;
        if (enabled == null || !enabled) {
            LOGGER.warn("### [TraceAlert] dispatcher disabled, skip init. plugin.logfilereporter.alert.enabled={}",
                    enabled);
            return null;
        }

        final List<TraceAnomalyListener> listeners = TraceAnomalyListenerLoader.loadAll();
        TraceAlertBootstrapLog.logListeners(listeners);
        final TraceEvaluator evaluator = TraceEvaluator.fromConfig(listeners);
        LOGGER.info("### [TraceAlert] dispatcher ready, totalListenerCount={}", listeners.size());
        TraceAlertMetrics.get().markDispatcherInitialized();
        return new AsyncTraceAlertDispatcher(evaluator, listeners);
    }

    public void afterTraceMerged(final String traceId, final Map<String, Object> mergedMap) {
        if (traceId == null || mergedMap == null) {
            return;
        }
        final TraceSnapshot snapshot = TraceSnapshot.fromMergedMap(traceId, mergedMap);
        final TraceEvaluator.EvaluationResult result = evaluator.evaluate(snapshot);
        if (!result.hasAlert()) {
            return;
        }

        final EnumSet<AlertType> pending = EnumSet.noneOf(AlertType.class);
        for (AlertType type : result.getAlertTypes()) {
            if (notifiedFlags.shouldNotify(traceId, type)) {
                pending.add(type);
            }
        }
        if (pending.isEmpty()) {
            TraceAlertMetrics.get().recordDispatchSkippedDuplicate();
            if (LOGGER.isDebugEnable()) {
                LOGGER.debug("### [TraceAlert] trace [{}] matched {} but already notified, skip.",
                        traceId, result.getAlertTypes());
            }
            return;
        }

        TraceAlertMetrics.get().recordDispatchSubmitted();
/* ==== 减少这种持续性的日志输出
        LOGGER.info("### [TraceAlert] dispatch traceId={}, alertTypes={}, entryOperation={}, durationMs={}, "
                        + "thresholdMs={}, errorSpanCount={}",
                traceId, pending, result.getEntryOperation(), result.getDurationMs(),
                result.getThresholdMs(), result.getErrorSpanCount());
*/
        notifiedFlags.markNotified(traceId, pending);
        final TraceAlertEvent event = new TraceAlertEvent(
                traceId,
                snapshot.getService() != null ? snapshot.getService() : Config.Agent.SERVICE_NAME,
                snapshot.getServiceInstance() != null ? snapshot.getServiceInstance() : Config.Agent.INSTANCE_NAME,
                pending,
                result.getEntryOperation(),
                result.getUrl(),
                result.getDurationMs(),
                result.getErrorSpanCount(),
                mergedMap);

        executor.execute(new DispatchTask(event));
    }

    public void shutdown() {
        executor.shutdown();
    }

    private final class DispatchTask implements Runnable {

        private final TraceAlertEvent event;

        private DispatchTask(final TraceAlertEvent event) {
            this.event = event;
        }

        @Override
        public void run() {
            for (TraceAnomalyListener listener : listeners) {
                if (listener instanceof NoOpTraceAnomalyListener) {
                    continue;
                }
                try {
                    listener.onTraceAlert(event);
                } catch (Throwable t) {
                    TraceAlertMetrics.get().recordListenerInvocationFailed();
                    LOGGER.error(t, "### [TraceAlert] TraceAnomalyListener failed for trace [{}].",
                            event.getTraceId());
                }
            }
        }
    }
}
