package org.apache.skywalking.apm.agent.core.reporter.logfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LogCollection {
	private List<Log> logs = new ArrayList<Log>();

	public LogCollection(List<Log> logs) {
		super();
		this.logs = logs;
	}

	public List<Log> getLogs() {
		return logs;
	}

	public void addLog(Log log) {
		logs.add(log);
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		// 将每个Log对象转换为Map，再放入logs的List中
		List<Map<String, Object>> logMaps = new ArrayList<>();
		for (Log log : logs) {
			Map<String, Object> logMap = new java.util.HashMap<>();
			logMap.put("traceId", log.getTraceId());
			logMap.put("traceSegmentId", log.getTraceSegmentId());
			logMap.put("service", log.getService());
			logMap.put("serviceInstance", log.getServiceInstance());
			logMap.put("isSizeLimited", log.getIsSizeLimited());
			// 处理spans
			List<Map<String, Object>> spanMaps = new ArrayList<>();
			if (log.getSpans() != null) {
				for (Log.SpanInfo span : log.getSpans()) {
					Map<String, Object> spanMap = new java.util.HashMap<>();
					spanMap.put("spanId", span.getSpanId());
					spanMap.put("operationName", span.getOperationName());
					spanMap.put("startTime", span.getStartTime());
					spanMap.put("endTime", span.getEndTime());
					spanMap.put("spanType", span.getSpanType());
					spanMap.put("spanLayer", span.getSpanLayer());
					spanMap.put("componentId", span.getComponentId());
					spanMap.put("isError", span.getIsError());
					spanMaps.add(spanMap);
				}
			}
			logMap.put("spans", spanMaps);
			logMaps.add(logMap);
		}
		map.put("logs", logMaps);
		return map;
	}
}