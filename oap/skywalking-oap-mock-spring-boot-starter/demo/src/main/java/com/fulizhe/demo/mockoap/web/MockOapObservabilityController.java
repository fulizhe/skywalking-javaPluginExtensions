package com.fulizhe.demo.mockoap.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fulizhe.observability.mockoap.MockOapGrpcServer;
import com.fulizhe.observability.mockoap.MockOapProperties;
import com.fulizhe.observability.mockoap.jvmmetrics.JVMMetricBuffer;
import com.fulizhe.observability.mockoap.log.LogBuffer;
import com.fulizhe.observability.mockoap.log.MockOapLogRecord;
import com.fulizhe.observability.mockoap.trace.MockOapSpan;
import com.fulizhe.observability.mockoap.trace.MockOapSpanRef;
import com.fulizhe.observability.mockoap.trace.MockOapTraceRecord;
import com.fulizhe.observability.mockoap.trace.TraceGraphAssembler;
import com.fulizhe.observability.mockoap.trace.TraceSegmentBuffer;

/**
 * mock-OAP 采集可观测接口(接收 go/node/java 等各语言 SW agent 上报)。
 *
 * <p>页面: 列表 {@code /mock-oap.html}、链路图 {@code /mock-oap-trace.html?traceId=...}。
 */
@RestController
@RequestMapping("/mock-oap")
public class MockOapObservabilityController {

	private final MockOapProperties properties;
	private final TraceSegmentBuffer buffer;
	private final LogBuffer logBuffer;
	private final JVMMetricBuffer jvmMetricBuffer;
	private final MockOapGrpcServer server;

	public MockOapObservabilityController(MockOapProperties properties, TraceSegmentBuffer buffer, LogBuffer logBuffer,
			JVMMetricBuffer jvmMetricBuffer, MockOapGrpcServer server) {
		this.properties = properties;
		this.buffer = buffer;
		this.logBuffer = logBuffer;
		this.jvmMetricBuffer = jvmMetricBuffer;
		this.server = server;
	}

	/** 采集状态:开关/端口/运行状态与接收计数。 */
	@GetMapping("/status")
	public Map<String, Object> status() {
		Map<String, Object> result = new LinkedHashMap<String, Object>();
		result.put("enabled", properties.isEnabled());
		result.put("configuredPort", properties.getPort());
		result.put("listenPort", server.getPort());
		result.put("running", server.isRunning());
		result.put("agentClassesVisible", server.isAgentClassesVisible());
		result.put("agentBooted", server.isAgentBooted());
		result.put("skipReason", server.getSkipReason());
		result.put("bufferSize", buffer.size());
		result.put("bufferCapacity", buffer.capacity());
		result.put("receivedTotal", buffer.receivedTotal());
		result.put("otlpReceivedRecords", buffer.otlpReceivedRecords());
		result.put("otlpAcceptedSpans", server.getOtlpAcceptedSpans());
		result.put("otlpRejectedSpans", server.getOtlpRejectedSpans());
		result.put("otlpQueueEvictedSpans", server.getOtlpQueueEvictedSpans());
		result.put("otlpLostSpans", server.getOtlpLostSpans());
		result.put("logBufferSize", logBuffer.size());
		result.put("logBufferCapacity", logBuffer.capacity());
		result.put("logReceivedTotal", logBuffer.receivedTotal());
		result.put("logDroppedSelfTotal", logBuffer.droppedSelfTotal());
		result.put("jvmMetricBufferSize", jvmMetricBuffer.size());
		result.put("jvmMetricBufferCapacity", jvmMetricBuffer.capacity());
		result.put("jvmMetricReceivedTotal", jvmMetricBuffer.receivedTotal());
		result.put("traceDroppedByCapacity", buffer.droppedByCapacity());
		result.put("logDroppedByCapacity", logBuffer.droppedByCapacity());
		result.put("jvmMetricDroppedByCapacity", jvmMetricBuffer.droppedByCapacity());
		result.put("ingest", server.getIngestStats());
		return result;
	}

	/** 最近接收的 JVM 指标批次(最新在前)。 */
	@GetMapping("/jvm-metrics")
	public Object jvmMetrics(@RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
		return jvmMetricBuffer.snapshot(limit);
	}

	/** JVM 指标缓存汇总计数。 */
	@GetMapping("/jvm-metrics/stats")
	public Object jvmMetricStats() {
		return jvmMetricBuffer.stats();
	}

	/** 原始段列表(扁平,便于排查)。 */
	@GetMapping("/traces")
	public Object traces() {
		return buffer.snapshot();
	}

	/**
	 * 接收到的日志(最新在前),供 {@code /mock-oap-logs.html} 与链路页「日志」页签使用。
	 *
	 * <p>参数全部可在页面上操作:
	 * <ul>
	 * <li>{@code limit} 返回条数(默认 100)</li>
	 * <li>{@code traceId} 只看某条链路的日志(精确匹配)</li>
	 * <li>{@code level} 按级别过滤(大小写不敏感;级别由 {@code LogData} 尽力抽取)</li>
	 * <li>{@code q} 正文/服务名/端点的大小写不敏感子串</li>
	 * </ul>
	 * 采集器自身的日志已被拒收,不会出现在这里(计数见 {@code /status} 的 {@code logDroppedSelfTotal})。
	 */
	@GetMapping("/logs")
	public Object logs(@RequestParam(value = "limit", required = false, defaultValue = "100") int limit,
			@RequestParam(value = "traceId", required = false) String traceId,
			@RequestParam(value = "level", required = false) String level,
			@RequestParam(value = "q", required = false) String q) {
		return logBuffer.query(traceId, level, q, limit);
	}

	/** 日志缓冲的汇总计数(状态页/面板用)。 */
	@GetMapping("/logs/stats")
	public Object logStats() {
		return logBuffer.stats();
	}

	/** 按 traceId 聚合的链路清单(入口页用)。 */
	@GetMapping("/graphs")
	public Object graphs(@RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
		return TraceGraphAssembler.listTraces(buffer.snapshot(), limit);
	}

	/**
	 * 组装一条链路图(服务端算好的树)。
	 *
	 * <p>注意:用 query 参数而非 path 变量 —— traceId 自带点号,path 变量会被当成文件扩展名截断。
	 */
	@GetMapping("/trace")
	public Object trace(@RequestParam("traceId") String traceId) {
		List<MockOapTraceRecord> records = buffer.findByTraceId(traceId);
		return TraceGraphAssembler.assemble(traceId, records);
	}

	/**
	 * 某条 traceId 下的**原始段**(含 span 明细),供前端自己建树/切换视图。
	 *
	 * <p>字段按 SkyWalking trace 视图页的习惯命名(spanType/spanLayer/isError/tagList/refs),
	 * tagList 用 {key,value};
	 * componentName 这里回落为 spanLayer(Http/Database/...) —— mock-OAP 没有组件名库。
	 *
	 * <p>日志按 span 挂载:{@code logsList} 只包含 traceId + traceSegmentId + spanId 都对得上的
	 * 日志(最新在前),{@code logsCount} 是它的条数。只带 traceId、定位不到 span 的日志仍可从
	 * {@code /mock-oap/logs?traceId=} 查到。
	 */
	@GetMapping("/segments")
	public Object segments(@RequestParam("traceId") String traceId) {
		List<MockOapTraceRecord> records = buffer.findByTraceId(traceId);
		List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(records.size());
		for (MockOapTraceRecord seg : records) {
			Map<String, Object> s = new LinkedHashMap<String, Object>();
			s.put("source", seg.getSource() == null ? "SKYWALKING" : seg.getSource());
			s.put("recordId", seg.getRecordId() == null ? seg.getTraceSegmentId() : seg.getRecordId());
			s.put("traceId", seg.getTraceId());
			s.put("traceSegmentId", seg.getTraceSegmentId());
			s.put("service", seg.getService());
			s.put("serviceInstance", seg.getServiceInstance());

			List<Map<String, Object>> spans = new ArrayList<Map<String, Object>>(seg.getSpans().size());
			for (MockOapSpan span : seg.getSpans()) {
				Map<String, Object> sp = new LinkedHashMap<String, Object>();
				sp.put("spanId", span.getSpanIdString() == null ? span.getSpanId() : span.getSpanIdString());
				sp.put("parentSpanId", span.getSpanIdString() == null ? span.getParentSpanId() : span.getParentSpanIdString());
				sp.put("operationName", span.getOperationName());
				sp.put("spanType", span.getSpanType());
				sp.put("spanLayer", span.getSpanLayer());
				sp.put("componentId", span.getComponentId());
				sp.put("componentName", span.getSpanLayer() == null ? "Unknown" : span.getSpanLayer());
				sp.put("peer", span.getPeer());
				sp.put("startTime", span.getStartTime());
				sp.put("endTime", span.getEndTime());
				sp.put("isError", span.isError());
				sp.put("tagList", tagList(span));
				sp.put("refs", refList(span));
				if (span.getSpanIdString() != null) {
					sp.put("source", MockOapTraceRecord.SOURCE_OTLP);
					sp.put("traceState", span.getOtlpTraceState());
					sp.put("flags", span.getOtlpFlags());
					sp.put("statusCode", span.getOtlpStatusCode());
					sp.put("statusMessage", span.getOtlpStatusMessage());
					sp.put("scopeName", span.getOtlpScopeName());
					sp.put("scopeVersion", span.getOtlpScopeVersion());
					sp.put("scopeSchemaUrl", span.getOtlpScopeSchemaUrl());
					sp.put("scopeAttributes", span.getOtlpScopeAttributes());
					sp.put("attributes", span.getOtlpAttributes());
					sp.put("events", span.getOtlpEvents());
					sp.put("links", span.getOtlpLinks());
					sp.put("droppedAttributesCount", span.getOtlpDroppedAttributesCount());
					sp.put("droppedEventsCount", span.getOtlpDroppedEventsCount());
					sp.put("droppedLinksCount", span.getOtlpDroppedLinksCount());
				}
				List<MockOapLogRecord> spanLogs = seg.isOtlp()
						? new ArrayList<MockOapLogRecord>()
						: logBuffer.findBySpan(traceId, seg.getTraceSegmentId(), span.getSpanId());
				sp.put("logsList", spanLogs);
				sp.put("logsCount", spanLogs.size());
				spans.add(sp);
			}
			s.put("spans", spans);
			out.add(s);
		}
		return out;
	}

	private static List<Map<String, Object>> tagList(MockOapSpan span) {
		List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
		for (Map.Entry<String, String> e : span.getTags().entrySet()) {
			Map<String, Object> t = new LinkedHashMap<String, Object>();
			t.put("key", e.getKey());
			t.put("value", e.getValue());
			list.add(t);
		}
		return list;
	}

	private static List<Map<String, Object>> refList(MockOapSpan span) {
		List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
		for (MockOapSpanRef ref : span.getRefs()) {
			list.add(ref.toMap());
		}
		return list;
	}
}
