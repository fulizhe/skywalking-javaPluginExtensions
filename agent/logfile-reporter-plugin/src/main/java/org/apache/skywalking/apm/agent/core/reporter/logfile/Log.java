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
     * 将Log对象转换为Map，便于序列化或外部系统使用
     */
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
                spanMap.put("operationName", span.getOperationName());
                spanMap.put("startTime", span.getStartTime());
                spanMap.put("endTime", span.getEndTime());
                spanMap.put("spanType", span.getSpanType());
                spanMap.put("spanLayer", span.getSpanLayer());
                spanMap.put("componentId", span.getComponentId());
                spanMap.put("isError", span.getIsError());
                spanMap.put("logsCount", span.getLogsCount());
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
        private String operationName;
        private long startTime;
        private long endTime;
        private String spanType;
        private String spanLayer;
        private int componentId;
        private boolean isError;
        private int logsCount;
        private List<Map<String, Object>> tagList;

        // Getter方法
        public int getSpanId() {
            return spanId;
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
        	return logsCount;
        }
		public List<Map<String, Object>> getTagList() {
			return tagList;
		}        

        // Setter方法
        public void setSpanId(int spanId) {
            this.spanId = spanId;
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
		public void setLogCount(int logsCount) {
			this.logsCount = logsCount;
		}
		public void setTagList(List<Map<String, Object>> tagList) {
			this.tagList = tagList;
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