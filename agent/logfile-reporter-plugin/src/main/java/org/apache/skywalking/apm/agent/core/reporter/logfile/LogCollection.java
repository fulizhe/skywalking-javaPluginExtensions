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
		// 优化：直接复用Log对象的toMap方法，提升代码复用性和可读性
		Map<String, Object> map = new HashMap<>();
		List<Map<String, Object>> logMaps = new ArrayList<>();
		for (Log log : logs) {
			// 直接调用Log的toMap方法，避免重复造轮子
			logMaps.add(log.toMap());
		}
		map.put("logs", logMaps);
		return map;
	}
}