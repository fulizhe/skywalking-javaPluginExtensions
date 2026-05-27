package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * 默认空实现，告警未配置时使用。
 */
public class NoOpTraceAnomalyListener implements TraceAnomalyListener {

    @Override
    public void onTraceAlert(final TraceAlertEvent event) {
        // no-op
    }
}
