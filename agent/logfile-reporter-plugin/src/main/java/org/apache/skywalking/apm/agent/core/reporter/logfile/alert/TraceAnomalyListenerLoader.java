package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;

/**
 * 加载 {@link TraceAnomalyListener}：Java SPI、配置类名、内置 HTTP webhook。
 */
final class TraceAnomalyListenerLoader {

    private static final ILog LOGGER = LogManager.getLogger(TraceAnomalyListenerLoader.class);

    private TraceAnomalyListenerLoader() {
    }

    static List<TraceAnomalyListener> loadAll() {
        LOGGER.info("### [TraceAlert] loading TraceAnomalyListener implementations...");
        final List<TraceAnomalyListener> listeners = new ArrayList<>();
        final int spiBefore = listeners.size();
        loadFromServiceLoader(listeners);
        LOGGER.info("### [TraceAlert] ServiceLoader loaded {} listener(s).", listeners.size() - spiBefore);

        final int beforeConfigClass = listeners.size();
        loadFromConfigClass(listeners);
        if (listeners.size() > beforeConfigClass) {
            LOGGER.info("### [TraceAlert] configured listener_class loaded: {}",
                    LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.LISTENER_CLASS);
        }

        final int beforeHttp = listeners.size();
        loadHttpListenerIfConfigured(listeners);
        if (listeners.size() > beforeHttp) {
            LOGGER.info("### [TraceAlert] HttpTraceAnomalyListener registered.");
        } else {
            LOGGER.warn("### [TraceAlert] HttpTraceAnomalyListener not registered (no webhook_url/webhook_path).");
        }

        if (listeners.isEmpty()) {
            LOGGER.warn("### [TraceAlert] no listener found, fallback to NoOpTraceAnomalyListener.");
            listeners.add(new NoOpTraceAnomalyListener());
        }
        return listeners;
    }

    private static void loadFromServiceLoader(final List<TraceAnomalyListener> listeners) {
        try {
            final Iterator<TraceAnomalyListener> iterator = ServiceLoader.load(TraceAnomalyListener.class,
                    TraceAnomalyListener.class.getClassLoader()).iterator();
            while (iterator.hasNext()) {
                final TraceAnomalyListener listener = iterator.next();
                listeners.add(listener);
                LOGGER.info("### [TraceAlert] SPI listener: {}", listener.getClass().getName());
            }
        } catch (Throwable t) {
            LOGGER.error(t, "Failed to load TraceAnomalyListener via ServiceLoader.");
        }
    }

    private static void loadFromConfigClass(final List<TraceAnomalyListener> listeners) {
        final String className = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.LISTENER_CLASS;
        if (className == null || className.trim().isEmpty()) {
            LOGGER.debug("### [TraceAlert] listener_class not configured, skip.");
            return;
        }
        try {
            final Class<?> clazz = Class.forName(className.trim(), true,
                    TraceAnomalyListener.class.getClassLoader());
            if (!TraceAnomalyListener.class.isAssignableFrom(clazz)) {
                LOGGER.warn("Configured listener class [{}] does not implement TraceAnomalyListener.", className);
                return;
            }
            listeners.add((TraceAnomalyListener) clazz.getDeclaredConstructor().newInstance());
        } catch (Throwable t) {
            LOGGER.error(t, "Failed to instantiate TraceAnomalyListener from class [{}].", className);
        }
    }

    private static void loadHttpListenerIfConfigured(final List<TraceAnomalyListener> listeners) {
        final HttpTraceAnomalyListener listener = HttpTraceAnomalyListener.fromConfig();
        if (listener != null) {
            listeners.add(listener);
        }
    }
}
