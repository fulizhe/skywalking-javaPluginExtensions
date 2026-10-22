package com.fulizhe.observability.mockoap.log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条由外部 agent 上报、经 mock-OAP 接收并抽取的日志快照(对应协议 {@code logging.v3.LogData})。
 *
 * <p>协议本身没有 level 字段:{@link #level} 由 {@link MockOapLogs#detectLevel} 尽力而为地抽取
 * (优先 tags 里的 {@code level},否则从正文解析)。
 */
public class MockOapLogRecord {

	/** 上报方侧的日志时间(毫秒,{@code LogData.timestamp})。 */
	private long timestamp;

	/** 上报方服务名。 */
	private String service;

	/** 上报方实例名。 */
	private String serviceInstance;

	/** 日志所在端点({@code LogData.endpoint},可能是 URL/类名等)。 */
	private String endpoint;

	/** 分层({@code LogData.layer},如 Http/Database)。 */
	private String layer;

	/** 正文类型:TEXT / JSON / YAML / NONE。 */
	private String contentType;

	/** 日志级别(尽力抽取,可能为空)。 */
	private String level;

	/** 正文内容(TEXT/JSON/YAML 已展平为字符串)。 */
	private String content;

	/** 链路 id(来自 {@code TraceContext.traceId};无上下文时为空)。 */
	private String traceId;

	/** 链路段 id(来自 {@code TraceContext.traceSegmentId})。 */
	private String traceSegmentId;

	/** span id(来自 {@code TraceContext.spanId})。 */
	private int spanId;

	/** 上报携带的标签(顺序保留)。 */
	private Map<String, String> tags = new LinkedHashMap<String, String>();

	/** 本进程接收该条日志的时刻(毫秒)。 */
	private long receivedAt;

	public long getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
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

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

	public String getLayer() {
		return layer;
	}

	public void setLayer(String layer) {
		this.layer = layer;
	}

	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public String getLevel() {
		return level;
	}

	public void setLevel(String level) {
		this.level = level;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
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

	public int getSpanId() {
		return spanId;
	}

	public void setSpanId(int spanId) {
		this.spanId = spanId;
	}

	public Map<String, String> getTags() {
		return tags;
	}

	public void setTags(Map<String, String> tags) {
		this.tags = tags == null ? new LinkedHashMap<String, String>() : tags;
	}

	public long getReceivedAt() {
		return receivedAt;
	}

	public void setReceivedAt(long receivedAt) {
		this.receivedAt = receivedAt;
	}
}
