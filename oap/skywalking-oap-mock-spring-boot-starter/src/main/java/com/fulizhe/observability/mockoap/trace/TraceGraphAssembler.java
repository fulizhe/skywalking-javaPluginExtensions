package com.fulizhe.observability.mockoap.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把同一 traceId 下的多个链路段(可能来自不同语言的服务)按跨进程引用组装成一棵树,
 * 供链路图(waterfall)渲染。
 *
 * <p>父子判定顺序:
 * <ol>
 *   <li>span 的 refs 里指向父段的引用(跨进程)→ 父节点 = 父段#父spanId</li>
 *   <li>同段内 parentSpanId(段内父子)</li>
 *   <li>都没有 → 根</li>
 * </ol>
 * 节点按 DFS 顺序输出,并带 depth / parentKey,前端据此做缩进与层级。
 */
public final class TraceGraphAssembler {

	private TraceGraphAssembler() {
	}

	/** 按 traceId 汇总出的"链路清单"条目(用于入口页选一条看)。 */
	public static List<Map<String, Object>> listTraces(List<MockOapTraceRecord> records, int limit) {
		Map<String, Map<String, Object>> byTrace = new LinkedHashMap<String, Map<String, Object>>();
		for (MockOapTraceRecord r : records) {
			String traceId = r.getTraceId();
			if (traceId == null) {
				continue;
			}
			Map<String, Object> item = byTrace.get(traceId);
			if (item == null) {
				item = new LinkedHashMap<String, Object>();
				item.put("traceId", traceId);
				item.put("services", new LinkedHashSet<String>());
				item.put("segmentCount", 0);
				item.put("spanCount", 0);
				item.put("startTime", Long.MAX_VALUE);
				item.put("endTime", Long.MIN_VALUE);
				item.put("error", false);
				item.put("lastReceivedAt", 0L);
				byTrace.put(traceId, item);
			}
			item.put("segmentCount", ((Integer) item.get("segmentCount")) + 1);
			item.put("spanCount", ((Integer) item.get("spanCount")) + r.getSpanCount());
			if (r.getStartTime() > 0) {
				item.put("startTime", Math.min((Long) item.get("startTime"), r.getStartTime()));
			}
			item.put("endTime", Math.max((Long) item.get("endTime"), r.getEndTime()));
			item.put("error", ((Boolean) item.get("error")) || r.isError());
			item.put("lastReceivedAt", Math.max((Long) item.get("lastReceivedAt"), r.getReceivedAt()));
			@SuppressWarnings("unchecked")
			Set<String> svcs = (Set<String>) item.get("services");
			if (r.getService() != null) {
				svcs.add(r.getService());
			}
		}

		List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
		for (Map<String, Object> item : byTrace.values()) {
			long s = (Long) item.get("startTime");
			long e = (Long) item.get("endTime");
			item.put("startTime", s == Long.MAX_VALUE ? 0 : s);
			item.put("endTime", e == Long.MIN_VALUE ? 0 : e);
			item.put("durationMs", e > s ? e - s : 0);
			item.put("services", new ArrayList<String>((Set<String>) item.get("services")));
			list.add(item);
		}
		Collections.sort(list, new Comparator<Map<String, Object>>() {
			@Override
			public int compare(Map<String, Object> a, Map<String, Object> b) {
				return ((Long) b.get("lastReceivedAt")).compareTo((Long) a.get("lastReceivedAt"));
			}
		});
		if (limit > 0 && list.size() > limit) {
			return new ArrayList<Map<String, Object>>(list.subList(0, limit));
		}
		return list;
	}

	/** 组装一条链路(按 traceId)。records 为该 traceId 下的全部段。 */
	public static Map<String, Object> assemble(String traceId, List<MockOapTraceRecord> records) {
		Map<String, Object> result = new LinkedHashMap<String, Object>();
		result.put("traceId", traceId);
		result.put("segmentCount", records == null ? 0 : records.size());

		// 1) 所有节点入表
		Map<String, Map<String, Object>> nodes = new LinkedHashMap<String, Map<String, Object>>();
		if (records != null) {
			for (MockOapTraceRecord seg : records) {
				for (MockOapSpan span : seg.getSpans()) {
					Map<String, Object> n = new LinkedHashMap<String, Object>();
					String recordId = seg.getRecordId() == null ? seg.getTraceSegmentId() : seg.getRecordId();
					String spanId = span.getSpanIdString() == null ? String.valueOf(span.getSpanId()) : span.getSpanIdString();
					String key = key(recordId, spanId);
					n.put("key", key);
					n.put("source", seg.getSource() == null ? "SKYWALKING" : seg.getSource());
					n.put("recordId", recordId);
					n.put("traceId", seg.getTraceId());
					n.put("traceSegmentId", seg.getTraceSegmentId());
					n.put("spanId", span.getSpanIdString() == null ? span.getSpanId() : span.getSpanIdString());
					n.put("parentSpanId", span.getSpanIdString() == null ? span.getParentSpanId() : span.getParentSpanIdString());
					n.put("service", seg.getService());
					n.put("serviceInstance", seg.getServiceInstance());
					n.put("resourceAttributes", seg.getResourceAttributes());
					n.put("resourceSchemaUrl", seg.getResourceSchemaUrl());
					n.put("operationName", span.getOperationName());
					n.put("scopeName", span.getOtlpScopeName());
					n.put("scopeVersion", span.getOtlpScopeVersion());
					n.put("scopeSchemaUrl", span.getOtlpScopeSchemaUrl());
					n.put("scopeAttributes", span.getOtlpScopeAttributes());
					n.put("attributes", span.getOtlpAttributes());
					n.put("events", span.getOtlpEvents());
					n.put("links", span.getOtlpLinks());
					n.put("statusCode", span.getOtlpStatusCode());
					n.put("statusMessage", span.getOtlpStatusMessage());
					n.put("traceState", span.getOtlpTraceState());
					n.put("flags", span.getOtlpFlags());
					n.put("spanType", span.getSpanType());
					n.put("spanLayer", span.getSpanLayer());
					n.put("componentId", span.getComponentId());
					n.put("peer", span.getPeer());
					n.put("startTime", span.getStartTime());
					n.put("endTime", span.getEndTime());
					n.put("durationMs", span.getEndTime() > span.getStartTime()
							? span.getEndTime() - span.getStartTime() : 0);
					n.put("error", span.isError());
					n.put("tags", span.getTags());
					n.put("refs", refsToMaps(span));
					n.put("sw8SegmentId", sw8Attribute(n, "sw8.segment.id"));
					n.put("sw8SpanId", sw8Attribute(n, "sw8.span.id"));
					n.put("sw8ParentSegmentId", sw8Attribute(n, "sw8.parent.segment.id"));
					n.put("sw8ParentSpanId", sw8Attribute(n, "sw8.parent.span.id"));
					nodes.put(key, n);
				}
			}
		}

		// 2) 解析父节点
		for (Map<String, Object> n : nodes.values()) {
			String parentKey = resolveParentKey(nodes, n);
			n.put("parentKey", parentKey);
		}

		// 3) 建 children + roots,DFS 打 depth
		Map<String, List<Map<String, Object>>> children = new LinkedHashMap<String, List<Map<String, Object>>>();
		List<Map<String, Object>> roots = new ArrayList<Map<String, Object>>();
		for (Map<String, Object> n : nodes.values()) {
			String parentKey = (String) n.get("parentKey");
			if (parentKey == null) {
				roots.add(n);
			} else {
				List<Map<String, Object>> list = children.get(parentKey);
				if (list == null) {
					list = new ArrayList<Map<String, Object>>();
					children.put(parentKey, list);
				}
				list.add(n);
			}
		}
		sortByStart(roots);
		for (List<Map<String, Object>> list : children.values()) {
			sortByStart(list);
		}

		List<Map<String, Object>> ordered = new ArrayList<Map<String, Object>>(nodes.size());
		Set<String> visited = new HashSet<String>();
		Deque<Object[]> stack = new java.util.ArrayDeque<Object[]>();
		for (int i = roots.size() - 1; i >= 0; i--) {
			stack.push(new Object[] { roots.get(i), 0 });
		}
		while (!stack.isEmpty()) {
			Object[] frame = stack.pop();
			@SuppressWarnings("unchecked")
			Map<String, Object> n = (Map<String, Object>) frame[0];
			int depth = (Integer) frame[1];
			String key = (String) n.get("key");
			if (!visited.add(key)) {
				continue;
			}
			n.put("depth", depth);
			ordered.add(n);
			List<Map<String, Object>> kids = children.get(key);
			if (kids != null) {
				for (int i = kids.size() - 1; i >= 0; i--) {
					stack.push(new Object[] { kids.get(i), depth + 1 });
				}
			}
		}
		// 兜底:环/孤儿节点也要出现
		for (Map<String, Object> n : nodes.values()) {
			if (!visited.contains((String) n.get("key"))) {
				n.put("depth", 0);
				ordered.add(n);
			}
		}

		// 4) 汇总与跨服务标注
		long traceStart = Long.MAX_VALUE;
		long traceEnd = Long.MIN_VALUE;
		boolean error = false;
		Set<String> services = new LinkedHashSet<String>();
		for (Map<String, Object> n : ordered) {
			long s = (Long) n.get("startTime");
			long e = (Long) n.get("endTime");
			if (s > 0 && s < traceStart) {
				traceStart = s;
			}
			if (e > traceEnd) {
				traceEnd = e;
			}
			error = error || (Boolean) n.get("error");
			if (n.get("service") != null) {
				services.add((String) n.get("service"));
			}
			String parentKey = (String) n.get("parentKey");
			Map<String, Object> parent = parentKey == null ? null : nodes.get(parentKey);
			n.put("crossService", parent != null
					&& parent.get("service") != null
					&& !parent.get("service").equals(n.get("service")));
		}
		if (traceStart == Long.MAX_VALUE) {
			traceStart = 0;
		}
		if (traceEnd == Long.MIN_VALUE) {
			traceEnd = 0;
		}

		result.put("services", new ArrayList<String>(services));
		result.put("startTime", traceStart);
		result.put("endTime", traceEnd);
		result.put("durationMs", traceEnd > traceStart ? traceEnd - traceStart : 0);
		result.put("spanCount", ordered.size());
		result.put("error", error);
		result.put("nodes", ordered);
		return result;
	}

	private static String resolveParentKey(Map<String, Map<String, Object>> nodes, Map<String, Object> n) {
		// 优先跨进程 ref(指向父段的某个 span)
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> refs = (List<Map<String, Object>>) n.get("refs");
		if (refs != null) {
			// 先挑 CrossProcess,再退到任意 ref
			for (int pass = 0; pass < 2; pass++) {
				for (Map<String, Object> ref : refs) {
					boolean crossProcess = "CrossProcess".equals(ref.get("refType"));
					if (pass == 0 && !crossProcess) {
						continue;
					}
					String parentSeg = (String) ref.get("parentTraceSegmentId");
					Object parentSpanId = ref.get("parentSpanId");
					if (parentSeg == null || parentSeg.isEmpty() || !(parentSpanId instanceof Integer)) {
						continue;
					}
					String candidate = key(parentSeg, (Integer) parentSpanId);
					if (nodes.containsKey(candidate) && !candidate.equals(n.get("key"))) {
						return candidate;
					}
					String sw8Candidate = findBySw8(nodes, parentSeg, String.valueOf(parentSpanId), n);
					if (sw8Candidate != null) {
						return sw8Candidate;
					}
				}
			}
		}
		// OTLP span explicitly carrying the SkyWalking parent coordinates (Go -> C++ hop).
		String sw8Parent = findBySw8(nodes, (String) n.get("sw8ParentSegmentId"),
				(String) n.get("sw8ParentSpanId"), n);
		if (sw8Parent != null) {
			return sw8Parent;
		}
		// 段内父子
		Object psb = n.get("parentSpanId");
		if (psb != null && !"-1".equals(String.valueOf(psb))) {
			String candidate = key((String) n.get("recordId"), String.valueOf(psb));
			if (nodes.containsKey(candidate) && !candidate.equals(n.get("key"))) {
				return candidate;
			}
			if (MockOapTraceRecord.SOURCE_OTLP.equals(n.get("source"))) {
				for (Map<String, Object> possibleParent : nodes.values()) {
					if (MockOapTraceRecord.SOURCE_OTLP.equals(possibleParent.get("source"))
							&& String.valueOf(psb).equals(String.valueOf(possibleParent.get("spanId")))
							&& String.valueOf(n.get("traceId")).equals(String.valueOf(possibleParent.get("traceId")))
							&& !possibleParent.get("key").equals(n.get("key"))) {
						return (String) possibleParent.get("key");
					}
				}
			}
		}
		return null;
	}

	private static String sw8Attribute(Map<String, Object> node, String key) {
		Object attributes = node.get("attributes");
		if (!(attributes instanceof Map)) {
			return null;
		}
		Object value = ((Map<?, ?>) attributes).get(key);
		return value == null ? null : String.valueOf(value);
	}

	/**
	 * Native SkyWalking segments identify a span by traceSegmentId + integer spanId, while
	 * OTLP spans have hex IDs. The C++ bridge publishes both coordinates as sw8.* attributes,
	 * so a cross-protocol parent edge can be resolved without pretending the protocols are equal.
	 */
	private static String findBySw8(Map<String, Map<String, Object>> nodes, String segmentId,
			String spanId, Map<String, Object> child) {
		if (segmentId == null || spanId == null) {
			return null;
		}
		for (Map<String, Object> candidate : nodes.values()) {
			if (candidate == child) {
				continue;
			}
			boolean segmentMatches = segmentId.equals(candidate.get("recordId"))
					|| segmentId.equals(candidate.get("sw8SegmentId"));
			String candidateSpanId = candidate.get("sw8SpanId") == null
					? String.valueOf(candidate.get("spanId")) : String.valueOf(candidate.get("sw8SpanId"));
			if (segmentMatches && spanId.equals(candidateSpanId)) {
				return (String) candidate.get("key");
			}
		}
		return null;
	}

	private static List<Map<String, Object>> refsToMaps(MockOapSpan span) {
		List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
		for (MockOapSpanRef ref : span.getRefs()) {
			list.add(ref.toMap());
		}
		return list;
	}

	private static String key(String recordId, String spanId) {
		return (recordId == null ? "?" : recordId) + "#" + spanId;
	}

	private static String key(String recordId, int spanId) {
		return key(recordId, String.valueOf(spanId));
	}

	private static void sortByStart(List<Map<String, Object>> list) {
		Collections.sort(list, new Comparator<Map<String, Object>>() {
			@Override
			public int compare(Map<String, Object> a, Map<String, Object> b) {
				long sa = ((Number) a.get("startTime")).longValue();
				long sb = ((Number) b.get("startTime")).longValue();
				return Long.compare(sa, sb);
			}
		});
	}
}
