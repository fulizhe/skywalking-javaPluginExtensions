package com.fulizhe.observability.mockoap.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条经 mock-OAP 接收并抽取的协议来源感知 trace 记录。
 *
 * <p>原生 SkyWalking 记录保留 segment 语义;OTLP 记录按 Resource + traceId 归组,不伪装为 segment。
 * 记录保留列表视图用的汇总字段与 span 明细,供链路图按 traceId 组装。
 */
public class MockOapTraceRecord {

	public static final String SOURCE_OTLP = "OTLP";

	/** 上报协议来源;原生 SkyWalking 记录为空或 SW,OTLP 记录为 OTLP。 */
	private String source;

	/** 通用记录身份;原生记录仍以 traceSegmentId 为身份。 */
	private String recordId;

	/** 链路 id(同一 trace 的所有段共用,可据此把多语言段串起来)。 */
	private String traceId;

	/** 段 id(去重键)。 */
	private String traceSegmentId;

	/** 上报方服务名。 */
	private String service;

	/** 上报方实例名。 */
	private String serviceInstance;

	/** Typed OTLP Resource attributes and schema URL; null for native SkyWalking segments. */
	private Map<String, Object> resourceAttributes = new LinkedHashMap<String, Object>();
	private String resourceSchemaUrl;

	/** 段内 span 数。 */
	private int spanCount;

	/** 入口 span 的 operationName(列表视图用);无入口 span 时为空。 */
	private String entryOperation;

	/** 段起始时间(毫秒,取段内最小 startTime)。 */
	private long startTime;

	/** 段结束时间(毫秒,取段内最大 endTime)。 */
	private long endTime;

	/** 段耗时(毫秒,endTime - startTime)。 */
	private long durationMs;

	/** 段内是否存在 isError 的 span。 */
	private boolean error;

	/** 本进程接收该段的时刻(毫秒)。 */
	private long receivedAt;

	/** 段内 span 明细(链路图用)。 */
	private List<MockOapSpan> spans = new ArrayList<MockOapSpan>();

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public boolean isOtlp() {
		return SOURCE_OTLP.equals(source);
	}

	public String getRecordId() {
		return recordId;
	}

	public void setRecordId(String recordId) {
		this.recordId = recordId;
	}

	public String getTraceId() {
		return traceId;
	}

	public void setTraceId(String traceId) {
		this.traceId = traceId;
	}

	public String getTraceSegmentId() {
		return traceSegmentId;
	}

	public void setTraceSegmentId(String traceSegmentId) {
		this.traceSegmentId = traceSegmentId;
	}

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

	public Map<String, Object> getResourceAttributes() {
		return resourceAttributes;
	}

	public void setResourceAttributes(Map<String, Object> resourceAttributes) {
		this.resourceAttributes = resourceAttributes;
	}

	public String getResourceSchemaUrl() {
		return resourceSchemaUrl;
	}

	public void setResourceSchemaUrl(String resourceSchemaUrl) {
		this.resourceSchemaUrl = resourceSchemaUrl;
	}

	public int getSpanCount() {
		return spanCount;
	}

	public void setSpanCount(int spanCount) {
		this.spanCount = spanCount;
	}

	public String getEntryOperation() {
		return entryOperation;
	}

	public void setEntryOperation(String entryOperation) {
		this.entryOperation = entryOperation;
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

	public long getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(long durationMs) {
		this.durationMs = durationMs;
	}

	public boolean isError() {
		return error;
	}

	public void setError(boolean error) {
		this.error = error;
	}

	public long getReceivedAt() {
		return receivedAt;
	}

	public void setReceivedAt(long receivedAt) {
		this.receivedAt = receivedAt;
	}

	public List<MockOapSpan> getSpans() {
		return spans;
	}

	public void setSpans(List<MockOapSpan> spans) {
		this.spans = spans;
	}

	/** Copy the record shell and span list so readers holding an earlier snapshot stay stable. */
	public MockOapTraceRecord copy() {
		MockOapTraceRecord copy = new MockOapTraceRecord();
		copy.setSource(source);
		copy.setRecordId(recordId);
		copy.setTraceId(traceId);
		copy.setTraceSegmentId(traceSegmentId);
		copy.setService(service);
		copy.setServiceInstance(serviceInstance);
		copy.setResourceAttributes(new LinkedHashMap<String, Object>(resourceAttributes));
		copy.setResourceSchemaUrl(resourceSchemaUrl);
		copy.setSpanCount(spanCount);
		copy.setEntryOperation(entryOperation);
		copy.setStartTime(startTime);
		copy.setEndTime(endTime);
		copy.setDurationMs(durationMs);
		copy.setError(error);
		copy.setReceivedAt(receivedAt);
		List<MockOapSpan> spanCopies = new ArrayList<MockOapSpan>(spans.size());
		for (MockOapSpan span : spans) spanCopies.add(span.copy());
		copy.setSpans(spanCopies);
		return copy;
	}
}
