package com.fulizhe.observability.mockoap.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.fulizhe.observability.mockoap.MockOapProperties;
import com.fulizhe.observability.mockoap.store.BoundedKeyedStore;
import com.fulizhe.observability.mockoap.store.StorePutResult;

/**
 * mock-OAP 接收到的链路段进程内有界缓存。
 *
 * <p>按 record id 去重(同一段重复上报只留一份),超出容量按插入序淘汰最旧;
 * 写线程 = 消费线程,读线程 = 观测页/链路图;快照返回副本,避免并发修改。
 */
public class TraceSegmentBuffer {

	private final BoundedKeyedStore<String, MockOapTraceRecord> store;
	private final AtomicLong receivedTotal = new AtomicLong();
	private final AtomicLong otlpReceivedRecords = new AtomicLong();

	public TraceSegmentBuffer(MockOapProperties properties) {
		this.store = new BoundedKeyedStore<String, MockOapTraceRecord>(properties.getMaxTraces());
	}

	/** 写入一条记录(按 record id 去重);超出容量时淘汰最旧一条。 */
	public void add(MockOapTraceRecord record) {
		if (record == null) {
			return;
		}
		String recordKey = record.getRecordId() == null ? record.getTraceSegmentId() : record.getRecordId();
		if (recordKey == null) {
			return;
		}
		StorePutResult<MockOapTraceRecord> result = store.put(recordKey, record);
		if (!record.isOtlp()) {
			receivedTotal.incrementAndGet();
		} else if (result.isInserted()) {
			otlpReceivedRecords.incrementAndGet();
		}
	}

	/** Atomically merge an OTLP record by span ID and retain its FIFO position as most recently updated. */
	public void mergeOtlp(MockOapTraceRecord incoming) {
		if (incoming == null || incoming.getRecordId() == null || !incoming.isOtlp()) {
			return;
		}
		StorePutResult<MockOapTraceRecord> result = store.compute(incoming.getRecordId(),
				(recordId, existing) -> mergeRecord(existing, incoming));
		if (result.isInserted()) {
			otlpReceivedRecords.incrementAndGet();
		}
	}

	private static MockOapTraceRecord mergeRecord(MockOapTraceRecord existing, MockOapTraceRecord incoming) {
		if (existing == null) {
			List<MockOapSpan> uniqueSpans = new ArrayList<MockOapSpan>();
			mergeSpans(uniqueSpans, incoming.getSpans());
			incoming.setSpans(uniqueSpans);
			refreshSummary(incoming);
			return incoming;
		}
		MockOapTraceRecord merged = existing.copy();
		mergeSpans(merged.getSpans(), incoming.getSpans());
		merged.setReceivedAt(System.currentTimeMillis());
		refreshSummary(merged);
		return merged;
	}

	/** Adds new span IDs and replaces repeated ones, keeping each span's first position. */
	private static void mergeSpans(List<MockOapSpan> target, List<MockOapSpan> incoming) {
		Map<String, Integer> indexes = new LinkedHashMap<String, Integer>();
		for (int i = 0; i < target.size(); i++) {
			indexes.put(target.get(i).getSpanIdString(), Integer.valueOf(i));
		}
		for (MockOapSpan span : incoming) {
			Integer index = indexes.get(span.getSpanIdString());
			if (index == null) {
				indexes.put(span.getSpanIdString(), Integer.valueOf(target.size()));
				target.add(span);
			} else {
				target.set(index.intValue(), span);
			}
		}
	}

	private static void refreshSummary(MockOapTraceRecord record) {
		long start = Long.MAX_VALUE;
		long end = Long.MIN_VALUE;
		boolean error = false;
		String entry = null;
		for (MockOapSpan span : record.getSpans()) {
			start = Math.min(start, span.getStartTime());
			end = Math.max(end, span.getEndTime());
			error |= span.isError();
			if (entry == null && span.getSpanType() != null
					&& (span.getSpanType().endsWith("SERVER") || span.getSpanType().endsWith("CONSUMER"))) {
				entry = span.getOperationName();
			}
		}
		record.setSpanCount(record.getSpans().size());
		record.setEntryOperation(entry);
		record.setStartTime(start == Long.MAX_VALUE ? 0 : start);
		record.setEndTime(end == Long.MIN_VALUE ? 0 : end);
		record.setDurationMs(end > start ? end - start : 0);
		record.setError(error);
	}

	/** 当前快照(副本),按接收顺序倒序(最新在前)。 */
	public List<MockOapTraceRecord> snapshot() {
		List<MockOapTraceRecord> refs = store.snapshot(0);
		List<MockOapTraceRecord> copy = new ArrayList<MockOapTraceRecord>(refs.size());
		for (MockOapTraceRecord record : refs) {
			copy.add(record.copy());
		}
		Collections.reverse(copy);
		return copy;
	}

	/** 取某个 traceId 下的全部段(用于组装链路图)。 */
	public List<MockOapTraceRecord> findByTraceId(String traceId) {
		List<MockOapTraceRecord> hit = new ArrayList<MockOapTraceRecord>();
		if (traceId == null) {
			return hit;
		}
		for (MockOapTraceRecord record : store.snapshot(0)) {
			if (traceId.equals(record.getTraceId())) {
				hit.add(record.copy());
			}
		}
		return hit;
	}

	/** 当前去重后的段条数。 */
	public int size() {
		return store.size();
	}

	/** 进程启动以来累计接收的原生段条数(含重复,不受容量淘汰影响)。 */
	public long receivedTotal() {
		return receivedTotal.get();
	}

	public long otlpReceivedRecords() {
		return otlpReceivedRecords.get();
	}

	public long droppedByCapacity() {
		return store.droppedByCapacity();
	}

	public int capacity() {
		return store.capacity();
	}

	public Map<String, Object> stats() {
		Map<String, Object> stats = store.stats("storeReceivedTotal");
		stats.put("nativeReceivedTotal", receivedTotal());
		stats.put("otlpReceivedRecords", otlpReceivedRecords());
		return stats;
	}
}
