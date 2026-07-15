package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;

/**
 * 异步分发慢/错链路告警，同一 traceId 同类型告警在 TTL 窗口内只通知一次。
 * <p>
 * 采用生产者-消费者模式：
 * <ul>
 *   <li>生产者（业务线程）：{@link #afterTraceMerged} 评估后用 {@code offer} 非阻塞投递，队列满则丢弃并计数。</li>
 *   <li>消费者（单守护线程）：{@code run()} 循环 {@code take} 阻塞等待，逐一回调 {@link TraceAnomalyListener}。</li>
 * </ul>
 * </p>
 */
public class AsyncTraceAlertDispatcher implements Runnable {

    private static final ILog LOGGER = LogManager.getLogger(AsyncTraceAlertDispatcher.class);

    private static final int DEFAULT_DISPATCH_QUEUE_SIZE = 512;

    private final TraceEvaluator evaluator;
    private final List<TraceAnomalyListener> listeners;
    private final NotifiedFlagsCache notifiedFlags;
    private final ArrayBlockingQueue<TraceAlertEvent> queue;

    /** 消费线程，由构造方法启动，shutdown() 时中断。 */
    private final Thread consumerThread;
    private volatile boolean running = true;

    AsyncTraceAlertDispatcher(final TraceEvaluator evaluator, final List<TraceAnomalyListener> listeners) {
        this(evaluator, listeners, NotifiedFlagsCache.fromConfig());
    }

    AsyncTraceAlertDispatcher(final TraceEvaluator evaluator, final List<TraceAnomalyListener> listeners,
            final NotifiedFlagsCache notifiedFlags) {
        this.evaluator = evaluator;
        this.listeners = listeners;
        this.notifiedFlags = notifiedFlags;
        this.queue = new ArrayBlockingQueue<>(resolveDispatchQueueSize());

        this.consumerThread = new Thread(this, "LogfileTraceAlert");
        this.consumerThread.setDaemon(true);
        this.consumerThread.start();
        LOGGER.info("### [TraceAlert] dispatch consumer thread started, queueCapacity={}", queue.remainingCapacity());
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

    /**
     * 消费循环：阻塞等待队列中的告警事件，逐一回调监听器。
     * 线程被中断或 running 置 false 时退出。
     */
    @Override
    public void run() {
        while (running) {
            try {
                final TraceAlertEvent event = queue.take();
                dispatch(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 退出前排空队列，确保已入队的事件不丢失
        TraceAlertEvent remaining;
        while ((remaining = queue.poll()) != null) {
            dispatch(remaining);
        }
        LOGGER.info("### [TraceAlert] consumer thread exited.");
    }

    public void afterTraceMerged(final String traceId, final Map<String, Object> mergedMap) {
        if (traceId == null || mergedMap == null) {
            return;
        }
        // 提前判断：若 ERROR 和 SLOW 均已通知过，跳过 snapshot 构建与 evaluate，避免无谓的 span 遍历
        if (notifiedFlags.isAllNotified(traceId)) {
            TraceAlertMetrics.get().recordDispatchSkippedDuplicate();
            if (LOGGER.isDebugEnable()) {
                LOGGER.debug("### [TraceAlert] trace [{}] all alert types already notified, skip evaluate.",
                        traceId);
            }
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

        // 非阻塞投递，队列满则丢弃并计数
        if (!queue.offer(event)) {
            TraceAlertMetrics.get().recordDispatchRejected();
            if (LOGGER.isDebugEnable()) {
                LOGGER.debug("### [TraceAlert] dispatch queue full, discard trace [{}], alertTypes={}.",
                        traceId, pending);
            }
            return;
        }
        TraceAlertMetrics.get().recordDispatchSubmitted(pending);
    }

    public void shutdown() {
        running = false;
        consumerThread.interrupt();
    }

    private void dispatch(final TraceAlertEvent event) {
        for (TraceAnomalyListener listener : listeners) {
            if (listener instanceof NoOpTraceAnomalyListener) {
                continue;
            }
            try {
                listener.onTraceAlert(event);
            } catch (Throwable t) {
                TraceAlertMetrics.get().recordListenerInvocationFailed();
                LOGGER.error(t, "### [TraceAlert] TraceAnomalyListener failed for trace [{}].", event.getTraceId());
            }
        }
    }

    private static int resolveDispatchQueueSize() {
        final Integer configured = 512; //LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.DISPATCH_QUEUE_SIZE;
        return configured != null && configured > 0 ? configured : DEFAULT_DISPATCH_QUEUE_SIZE;
    }
}
