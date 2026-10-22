package com.fulizhe.observability.mockoap.trace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 段内一个跨进程引用(指向父段里的某个 span),用于把不同服务的段串成一条链路。
 */
public class MockOapSpanRef {

	/** CrossProcess / CrossThread */
	private String refType;

	/** 父段 id(= 上游服务上报段的 traceSegmentId) */
	private String parentTraceSegmentId;

	/** 父 span 在父段内的 id(0 表示父段的入口 span;>-1 才有效) */
	private int parentSpanId;

	/** 父服务名(agent 侧填的,可直接用于图上标注) */
	private String parentService;

	/** 父端点(操作名) */
	private String parentEndpoint;

	/** 对端网络地址 */
	private String networkAddressUsedAtPeer;

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<String, Object>();
		m.put("refType", refType);
		m.put("parentTraceSegmentId", parentTraceSegmentId);
		m.put("parentSpanId", parentSpanId);
		m.put("parentService", parentService);
		m.put("parentEndpoint", parentEndpoint);
		m.put("networkAddressUsedAtPeer", networkAddressUsedAtPeer);
		return m;
	}

	public MockOapSpanRef copy() {
		MockOapSpanRef copy = new MockOapSpanRef();
		copy.setRefType(refType);
		copy.setParentTraceSegmentId(parentTraceSegmentId);
		copy.setParentSpanId(parentSpanId);
		copy.setParentService(parentService);
		copy.setParentEndpoint(parentEndpoint);
		copy.setNetworkAddressUsedAtPeer(networkAddressUsedAtPeer);
		return copy;
	}

	public String getRefType() {
		return refType;
	}

	public void setRefType(String refType) {
		this.refType = refType;
	}

	public String getParentTraceSegmentId() {
		return parentTraceSegmentId;
	}

	public void setParentTraceSegmentId(String parentTraceSegmentId) {
		this.parentTraceSegmentId = parentTraceSegmentId;
	}

	public int getParentSpanId() {
		return parentSpanId;
	}

	public void setParentSpanId(int parentSpanId) {
		this.parentSpanId = parentSpanId;
	}

	public String getParentService() {
		return parentService;
	}

	public void setParentService(String parentService) {
		this.parentService = parentService;
	}

	public String getParentEndpoint() {
		return parentEndpoint;
	}

	public void setParentEndpoint(String parentEndpoint) {
		this.parentEndpoint = parentEndpoint;
	}

	public String getNetworkAddressUsedAtPeer() {
		return networkAddressUsedAtPeer;
	}

	public void setNetworkAddressUsedAtPeer(String networkAddressUsedAtPeer) {
		this.networkAddressUsedAtPeer = networkAddressUsedAtPeer;
	}
}
