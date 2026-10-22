package com.fulizhe.observability.mockoap.jvmmetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JVMMetricCollection 转换后的普通 Java 快照，避免读口依赖 protobuf 内部类型。 */
public class MockOapJvmMetricRecord {

	private String service;
	private String serviceInstance;
	private long receivedAt;
	private List<Map<String, Object>> metrics = new ArrayList<Map<String, Object>>();

	public String getService() {
		return service;
	}

	public void setService(String service) {
		this.service = service;
	}

	public String getServiceInstance() {
		return serviceInstance;
	}

	public void setServiceInstance(String serviceInstance) {
		this.serviceInstance = serviceInstance;
	}

	public long getReceivedAt() {
		return receivedAt;
	}

	public void setReceivedAt(long receivedAt) {
		this.receivedAt = receivedAt;
	}

	public List<Map<String, Object>> getMetrics() {
		return metrics;
	}

	public void setMetrics(List<Map<String, Object>> metrics) {
		this.metrics = metrics == null ? new ArrayList<Map<String, Object>>() : metrics;
	}
}
