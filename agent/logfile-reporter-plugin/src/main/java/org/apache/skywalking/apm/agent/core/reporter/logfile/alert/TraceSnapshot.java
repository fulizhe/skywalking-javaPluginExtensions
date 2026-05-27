package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.skywalking.apm.agent.core.reporter.logfile.Log;

/**
 * 单 JVM 内按 traceId 合并后的链路视图，供慢/错判定与扩展回调使用。
 */
public class TraceSnapshot {

    private final String traceId;
    private final String service;
    private final String serviceInstance;
    private final List<Log> logs;
    private final Map<String, Object> traceMap;

    public TraceSnapshot(final String traceId, final String service, final String serviceInstance,
            final List<Log> logs, final Map<String, Object> traceMap) {
        this.traceId = traceId;
        this.service = service;
        this.serviceInstance = serviceInstance;
        this.logs = logs == null ? Collections.<Log>emptyList() : Collections.unmodifiableList(new ArrayList<>(logs));
        this.traceMap = traceMap == null ? Collections.<String, Object>emptyMap() : traceMap;
    }

    public static TraceSnapshot fromMergedMap(final String traceId, final Map<String, Object> mergedMap) {
        if (mergedMap == null) {
            return new TraceSnapshot(traceId, null, null, Collections.<Log>emptyList(), Collections.<String, Object>emptyMap());
        }
        final List<Log> logs = new ArrayList<>();
        String service = null;
        String serviceInstance = null;
        final Object logsObj = mergedMap.get("logs");
        if (logsObj instanceof List) {
            for (Object item : (List<?>) logsObj) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    final Log log = Log.fromMap((Map<String, Object>) item);
                    logs.add(log);
                    if (service == null) {
                        service = log.getService();
                    }
                    if (serviceInstance == null) {
                        serviceInstance = log.getServiceInstance();
                    }
                }
            }
        }
        return new TraceSnapshot(traceId, service, serviceInstance, logs, mergedMap);
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

    public List<Log> getLogs() {
        return logs;
    }

    public Map<String, Object> getTraceMap() {
        return traceMap;
    }
}
