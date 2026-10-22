package com.fulizhe.observability.mockoap.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 段内一个 span 的快照(展示链路图所需的最小集)。
 */
public class MockOapSpan {

	/** 段内 span id */
	private int spanId;

	/** 段内父 span id;-1 表示段内根 span */
	private int parentSpanId;

	/** String representation for protocols whose span IDs are not signed 32-bit integers. */
	private String spanIdString;

	/** String parent ID; null means no parent. Native SkyWalking IDs keep using the integer fields. */
	private String parentSpanIdString;

	private String operationName;

	/** Entry / Exit / Local */
	private String spanType;

	/** Http / Database / RPCFramework / MQ / Cache / FAAS / Unknown */
	private String spanLayer;

	private long startTime;
	private long endTime;
	private boolean error;
	private int componentId;
	private String peer;

	/** 采到的 tag(值做了长度截断,防大字段把内存撑爆) */
	private Map<String, String> tags = new LinkedHashMap<String, String>();

	/** 跨进程引用(指向父段里的 span),用于把不同服务的段串成一条链路 */
	private List<MockOapSpanRef> refs = new ArrayList<MockOapSpanRef>();

	/** Full typed OTLP detail; null/empty for native spans. */
	private String otlpTraceState;
	private int otlpFlags;
	private String otlpStatusCode;
	private String otlpStatusMessage;
	private String otlpScopeName;
	private String otlpScopeVersion;
	private String otlpScopeSchemaUrl;
	private Map<String, Object> otlpScopeAttributes = new LinkedHashMap<String, Object>();
	private Map<String, Object> otlpAttributes = new LinkedHashMap<String, Object>();
	private List<Map<String, Object>> otlpEvents = new ArrayList<Map<String, Object>>();
	private List<Map<String, Object>> otlpLinks = new ArrayList<Map<String, Object>>();
	private int otlpDroppedAttributesCount;
	private int otlpDroppedEventsCount;
	private int otlpDroppedLinksCount;

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<String, Object>();
		m.put("spanId", spanIdString == null ? spanId : spanIdString);
		m.put("parentSpanId", spanIdString == null ? parentSpanId : parentSpanIdString);
		m.put("operationName", operationName);
		m.put("spanType", spanType);
		m.put("spanLayer", spanLayer);
		m.put("startTime", startTime);
		m.put("endTime", endTime);
		m.put("durationMs", endTime > startTime ? endTime - startTime : 0);
		m.put("error", error);
		m.put("componentId", componentId);
		m.put("peer", peer);
		m.put("tags", tags);
		m.put("refs", refs);
		if (spanIdString != null) {
			m.put("source", MockOapTraceRecord.SOURCE_OTLP);
			m.put("traceState", otlpTraceState);
			m.put("flags", otlpFlags);
			m.put("statusCode", otlpStatusCode);
			m.put("statusMessage", otlpStatusMessage);
			m.put("scopeName", otlpScopeName);
			m.put("scopeVersion", otlpScopeVersion);
			m.put("scopeSchemaUrl", otlpScopeSchemaUrl);
			m.put("scopeAttributes", otlpScopeAttributes);
			m.put("attributes", otlpAttributes);
			m.put("events", otlpEvents);
			m.put("links", otlpLinks);
			m.put("droppedAttributesCount", otlpDroppedAttributesCount);
			m.put("droppedEventsCount", otlpDroppedEventsCount);
			m.put("droppedLinksCount", otlpDroppedLinksCount);
		}
		return m;
	}

	public MockOapSpan copy() {
		MockOapSpan copy = new MockOapSpan();
		copy.setSpanId(spanId);
		copy.setParentSpanId(parentSpanId);
		copy.setSpanIdString(spanIdString);
		copy.setParentSpanIdString(parentSpanIdString);
		copy.setOperationName(operationName);
		copy.setSpanType(spanType);
		copy.setSpanLayer(spanLayer);
		copy.setStartTime(startTime);
		copy.setEndTime(endTime);
		copy.setError(error);
		copy.setComponentId(componentId);
		copy.setPeer(peer);
		copy.setTags(new LinkedHashMap<String, String>(tags));
		List<MockOapSpanRef> refCopies = new ArrayList<MockOapSpanRef>(refs.size());
		for (MockOapSpanRef ref : refs) refCopies.add(ref.copy());
		copy.setRefs(refCopies);
		copy.setOtlpTraceState(otlpTraceState);
		copy.setOtlpFlags(otlpFlags);
		copy.setOtlpStatusCode(otlpStatusCode);
		copy.setOtlpStatusMessage(otlpStatusMessage);
		copy.setOtlpScopeName(otlpScopeName);
		copy.setOtlpScopeVersion(otlpScopeVersion);
		copy.setOtlpScopeSchemaUrl(otlpScopeSchemaUrl);
		copy.setOtlpScopeAttributes(new LinkedHashMap<String, Object>(otlpScopeAttributes));
		copy.setOtlpAttributes(new LinkedHashMap<String, Object>(otlpAttributes));
		copy.setOtlpEvents(copyMaps(otlpEvents));
		copy.setOtlpLinks(copyMaps(otlpLinks));
		copy.setOtlpDroppedAttributesCount(otlpDroppedAttributesCount);
		copy.setOtlpDroppedEventsCount(otlpDroppedEventsCount);
		copy.setOtlpDroppedLinksCount(otlpDroppedLinksCount);
		return copy;
	}

	private static List<Map<String, Object>> copyMaps(List<Map<String, Object>> values) {
		List<Map<String, Object>> copies = new ArrayList<Map<String, Object>>(values.size());
		for (Map<String, Object> value : values) copies.add(new LinkedHashMap<String, Object>(value));
		return copies;
	}

	public String getOtlpTraceState() { return otlpTraceState; }
	public void setOtlpTraceState(String value) { this.otlpTraceState = value; }
	public int getOtlpFlags() { return otlpFlags; }
	public void setOtlpFlags(int value) { this.otlpFlags = value; }
	public String getOtlpStatusCode() { return otlpStatusCode; }
	public void setOtlpStatusCode(String value) { this.otlpStatusCode = value; }
	public String getOtlpStatusMessage() { return otlpStatusMessage; }
	public void setOtlpStatusMessage(String value) { this.otlpStatusMessage = value; }
	public String getOtlpScopeName() { return otlpScopeName; }
	public void setOtlpScopeName(String value) { this.otlpScopeName = value; }
	public String getOtlpScopeVersion() { return otlpScopeVersion; }
	public void setOtlpScopeVersion(String value) { this.otlpScopeVersion = value; }
	public String getOtlpScopeSchemaUrl() { return otlpScopeSchemaUrl; }
	public void setOtlpScopeSchemaUrl(String value) { this.otlpScopeSchemaUrl = value; }
	public Map<String, Object> getOtlpScopeAttributes() { return otlpScopeAttributes; }
	public void setOtlpScopeAttributes(Map<String, Object> value) { this.otlpScopeAttributes = value; }
	public Map<String, Object> getOtlpAttributes() { return otlpAttributes; }
	public void setOtlpAttributes(Map<String, Object> value) { this.otlpAttributes = value; }
	public List<Map<String, Object>> getOtlpEvents() { return otlpEvents; }
	public void setOtlpEvents(List<Map<String, Object>> value) { this.otlpEvents = value; }
	public List<Map<String, Object>> getOtlpLinks() { return otlpLinks; }
	public void setOtlpLinks(List<Map<String, Object>> value) { this.otlpLinks = value; }
	public int getOtlpDroppedAttributesCount() { return otlpDroppedAttributesCount; }
	public void setOtlpDroppedAttributesCount(int value) { this.otlpDroppedAttributesCount = value; }
	public int getOtlpDroppedEventsCount() { return otlpDroppedEventsCount; }
	public void setOtlpDroppedEventsCount(int value) { this.otlpDroppedEventsCount = value; }
	public int getOtlpDroppedLinksCount() { return otlpDroppedLinksCount; }
	public void setOtlpDroppedLinksCount(int value) { this.otlpDroppedLinksCount = value; }

	public String getSpanIdString() {
		return spanIdString;
	}

	public void setSpanIdString(String spanIdString) {
		this.spanIdString = spanIdString;
	}

	public String getParentSpanIdString() {
		return parentSpanIdString;
	}

	public void setParentSpanIdString(String parentSpanIdString) {
		this.parentSpanIdString = parentSpanIdString;
	}

	public List<MockOapSpanRef> getRefs() {
		return refs;
	}

	public void setRefs(List<MockOapSpanRef> refs) {
		this.refs = refs;
	}

	public int getSpanId() {
		return spanId;
	}

	public void setSpanId(int spanId) {
		this.spanId = spanId;
	}

	public int getParentSpanId() {
		return parentSpanId;
	}

	public void setParentSpanId(int parentSpanId) {
		this.parentSpanId = parentSpanId;
	}

	public String getOperationName() {
		return operationName;
	}

	public void setOperationName(String operationName) {
		this.operationName = operationName;
	}

	public String getSpanType() {
		return spanType;
	}

	public void setSpanType(String spanType) {
		this.spanType = spanType;
	}

	public String getSpanLayer() {
		return spanLayer;
	}

	public void setSpanLayer(String spanLayer) {
		this.spanLayer = spanLayer;
	}

	public long getStartTime() {
		return startTime;
	}

	public void setStartTime(long startTime) {
		this.startTime = startTime;
	}

	public long getEndTime() {
		return endTime;
	}

	public void setEndTime(long endTime) {
		this.endTime = endTime;
	}

	public boolean isError() {
		return error;
	}

	public void setError(boolean error) {
		this.error = error;
	}

	public int getComponentId() {
		return componentId;
	}

	public void setComponentId(int componentId) {
		this.componentId = componentId;
	}

	public String getPeer() {
		return peer;
	}

	public void setPeer(String peer) {
		this.peer = peer;
	}

	public Map<String, String> getTags() {
		return tags;
	}

	public void setTags(Map<String, String> tags) {
		this.tags = tags;
	}
}
