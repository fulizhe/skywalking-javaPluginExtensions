package org.apache.skywalking.apm.agent.core.reporter.logfile;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 存储Trace Segment及其Span信息的日志对象
 */
public class Log {
    private String traceId;
    private String traceSegmentId;
    private String service;
    private String serviceInstance;
    private boolean isSizeLimited;
    private List<SpanInfo> spans;

    // Getter方法
    public String getTraceId() {
        return traceId;
    }
    public String getTraceSegmentId() {
        return traceSegmentId;
    }
    public String getService() {
        return service;
    }
    public String getServiceInstance() {
        return serviceInstance;
    }
    public boolean getIsSizeLimited() {
        return isSizeLimited;
    }
    public List<SpanInfo> getSpans() {
        return spans;
    }

    // Setter方法
    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
    public void setTraceSegmentId(String traceSegmentId) {
        this.traceSegmentId = traceSegmentId;
    }
    public void setService(String service) {
        this.service = service;
    }
    public void setServiceInstance(String serviceInstance) {
        this.serviceInstance = serviceInstance;
    }
    public void setIsSizeLimited(boolean isSizeLimited) {
        this.isSizeLimited = isSizeLimited;
    }
    public void setSpans(List<SpanInfo> spans) {
        this.spans = spans;
    }

    /**
     * 从 {@link #toMap()} 结构还原 Log，供合并 trace 告警评估使用。
     */
    @SuppressWarnings("unchecked")
    public static Log fromMap(final Map<String, Object> map) {
        final Log log = new Log();
        if (map == null) {
            return log;
        }
        log.setTraceId(asString(map.get("traceId")));
        log.setTraceSegmentId(asString(map.get("traceSegmentId")));
        log.setService(asString(map.get("service")));
        log.setServiceInstance(asString(map.get("serviceInstance")));
        final Object sizeLimited = map.get("isSizeLimited");
        if (sizeLimited instanceof Boolean) {
            log.setIsSizeLimited((Boolean) sizeLimited);
        }
        final Object spansObj = map.get("spans");
        if (spansObj instanceof List) {
            final List<Log.SpanInfo> spans = new java.util.ArrayList<>();
            for (Object item : (List<Object>) spansObj) {
                if (item instanceof Map) {
                    spans.add(SpanInfo.fromMap((Map<String, Object>) item));
                }
            }
            log.setSpans(spans);
        }
        return log;
    }

    private static String asString(final Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("traceId", this.traceId);
        map.put("traceSegmentId", this.traceSegmentId);
        map.put("service", this.service);
        map.put("serviceInstance", this.serviceInstance);
        map.put("isSizeLimited", this.isSizeLimited);

        // 处理spans
        List<Map<String, Object>> spanMaps = new java.util.ArrayList<>();
        if (this.spans != null) {
            for (SpanInfo span : this.spans) {
                Map<String, Object> spanMap = new java.util.HashMap<>();
                spanMap.put("spanId", span.getSpanId());
                spanMap.put("parentSpanId", span.getParentSpanId());
                spanMap.put("operationName", span.getOperationName());
                spanMap.put("startTime", span.getStartTime());
                spanMap.put("endTime", span.getEndTime());
                spanMap.put("spanType", span.getSpanType());
                spanMap.put("spanLayer", span.getSpanLayer());
                spanMap.put("componentId", span.getComponentId());
                spanMap.put("isError", span.getIsError());
                spanMap.put("logsCount", span.getLogsCount());
                spanMap.put("logsList", span.getLogList());
                spanMap.put("tagList", span.getTagList());
                spanMaps.add(spanMap);
            }
        }
        map.put("spans", spanMaps);

        return map;
    }

    @Override
    public String toString() {
        return "Log{" +
                "traceId='" + traceId + '\'' +
                ", traceSegmentId='" + traceSegmentId + '\'' +
                ", service='" + service + '\'' +
                ", serviceInstance='" + serviceInstance + '\'' +
                ", isSizeLimited=" + isSizeLimited +
                ", spans=" + spans +
                '}';
    }

    /**
     * Span信息内部类
     */
    public static class SpanInfo {
        private int spanId;
        private int parentSpanId = -1;
        private String operationName;
        private long startTime;
        private long endTime;
        private String spanType;
        private String spanLayer;
        private int componentId;
        private boolean isError;
        private List<Map<String, Object>> tagList;
        private List<String> logList;

        // Getter方法
        public int getSpanId() {
            return spanId;
        }
        public int getParentSpanId() {
            return parentSpanId;
        }
        public String getOperationName() {
            return operationName;
        }
        public long getStartTime() {
            return startTime;
        }
        public long getEndTime() {
            return endTime;
        }
        public String getSpanType() {
            return spanType;
        }
        public String getSpanLayer() {
            return spanLayer;
        }
        public int getComponentId() {
            return componentId;
        }
        public boolean getIsError() {
            return isError;
        }        
        public int getLogsCount() {
        	return Objects.isNull(this.logList) ? 0 : this.logList.size();        	
        }
		public List<Map<String, Object>> getTagList() {
			return tagList;
		}
		public List<String> getLogList() {
			return logList;
		}  		

        // Setter方法
        public void setSpanId(int spanId) {
            this.spanId = spanId;
        }
        public void setParentSpanId(int parentSpanId) {
            this.parentSpanId = parentSpanId;
        }
        public void setOperationName(String operationName) {
            this.operationName = operationName;
        }
        public void setStartTime(long startTime) {
            this.startTime = startTime;
        }
        public void setEndTime(long endTime) {
            this.endTime = endTime;
        }
        public void setSpanType(String spanType) {
            this.spanType = spanType;
        }
        public void setSpanLayer(String spanLayer) {
            this.spanLayer = spanLayer;
        }
        public void setComponentId(int componentId) {
            this.componentId = componentId;
        }
        public void setIsError(boolean isError) {
            this.isError = isError;
        }
		public void setTagList(List<Map<String, Object>> tagList) {
			this.tagList = tagList;
		}  
		public void setLogList(List<String> collect) {
			this.logList=collect;
		}

        @SuppressWarnings("unchecked")
        public static SpanInfo fromMap(final Map<String, Object> map) {
            final SpanInfo spanInfo = new SpanInfo();
            if (map == null) {
                return spanInfo;
            }
            final Object spanId = map.get("spanId");
            if (spanId instanceof Number) {
                spanInfo.setSpanId(((Number) spanId).intValue());
            }
            final Object parentSpanId = map.get("parentSpanId");
            if (parentSpanId instanceof Number) {
                spanInfo.setParentSpanId(((Number) parentSpanId).intValue());
            }
            spanInfo.setOperationName(asString(map.get("operationName")));
            final Object startTime = map.get("startTime");
            if (startTime instanceof Number) {
                spanInfo.setStartTime(((Number) startTime).longValue());
            }
            final Object endTime = map.get("endTime");
            if (endTime instanceof Number) {
                spanInfo.setEndTime(((Number) endTime).longValue());
            }
            spanInfo.setSpanType(asString(map.get("spanType")));
            spanInfo.setSpanLayer(asString(map.get("spanLayer")));
            final Object componentId = map.get("componentId");
            if (componentId instanceof Number) {
                spanInfo.setComponentId(((Number) componentId).intValue());
            }
            final Object isError = map.get("isError");
            if (isError instanceof Boolean) {
                spanInfo.setIsError((Boolean) isError);
            }
            final Object tagList = map.get("tagList");
            if (tagList instanceof List) {
                spanInfo.setTagList((List<Map<String, Object>>) tagList);
            }
            final Object logList = map.get("logsList");
            if (logList instanceof List) {
                spanInfo.setLogList((List<String>) logList);
            }
            return spanInfo;
        }

        private static String asString(final Object value) {
            return value == null ? null : String.valueOf(value);
        }

        @Override
        public String toString() {
            return "SpanInfo{" +
                    "spanId=" + spanId +
                    ", operationName='" + operationName + '\'' +
                    ", startTime=" + startTime +
                    ", endTime=" + endTime +
                    ", spanType=" + spanType +
                    ", spanLayer=" + spanLayer +
                    ", componentId=" + componentId +
                    ", isError=" + isError +
                    '}';
        }


    }
}