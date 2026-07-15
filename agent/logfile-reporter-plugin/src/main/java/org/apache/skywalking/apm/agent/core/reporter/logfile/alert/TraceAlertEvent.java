package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 慢/错链路异步通知事件，SPI 与 HTTP 回调共用同一 payload 结构。
 */
public class TraceAlertEvent {

    private final String traceId;
    private final String service;
    private final String serviceInstance;
    private final Set<AlertType> alertTypes;
    private final String entryOperation;
    private final String url;
    private final long durationMs;
    private final int errorSpanCount;
    private final Map<String, Object> traceData;

    public TraceAlertEvent(final String traceId, final String service, final String serviceInstance,
            final Set<AlertType> alertTypes, final String entryOperation, final String url,
            final long durationMs, final int errorSpanCount, final Map<String, Object> traceData) {
        this.traceId = traceId;
        this.service = service;
        this.serviceInstance = serviceInstance;
        this.alertTypes = alertTypes == null ? EnumSet.noneOf(AlertType.class)
                : EnumSet.copyOf(alertTypes);
        this.entryOperation = entryOperation;
        this.url = url;
        this.durationMs = durationMs;
        this.errorSpanCount = errorSpanCount;
        this.traceData = traceData == null ? new HashMap<String, Object>() : traceData;
    }

    public Map<String, Object> toMap() {
        final Map<String, Object> map = new HashMap<>();
        map.put("traceId", traceId);
        map.put("service", service);
        map.put("serviceInstance", serviceInstance);
        final List<String> types = new ArrayList<>();
        for (AlertType type : alertTypes) {
            types.add(type.name());
        }
        map.put("alertTypes", types);
        map.put("entryOperation", entryOperation);
        map.put("url", url);
        map.put("durationMs", durationMs);
        map.put("errorSpanCount", errorSpanCount);
        map.put("logs", traceData.get("logs"));
        return map;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getService() {
        return service;
    }

    public String getServiceInstance() {
        return serviceInstance;
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

    public int getErrorSpanCount() {
        return errorSpanCount;
    }

    public Map<String, Object> getTraceData() {
        return traceData;
    }
}
