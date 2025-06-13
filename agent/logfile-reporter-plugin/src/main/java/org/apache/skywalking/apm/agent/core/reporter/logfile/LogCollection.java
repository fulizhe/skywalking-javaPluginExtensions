package org.apache.skywalking.apm.agent.core.reporter.logfile;

import java.util.ArrayList;
import java.util.List;

public class LogCollection {
	private List<Log> logs = new ArrayList<Log>();

	public LogCollection(List<Log> logs) {
		super();
		this.logs = logs;
	}

	public List<Log> getLogs() {
		return logs;
	}

}