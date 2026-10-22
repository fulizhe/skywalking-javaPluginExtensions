package com.fulizhe.observability.mockoap.log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.fulizhe.observability.mockoap.MockOapProperties;
import com.fulizhe.observability.mockoap.store.BoundedFifoStore;
import com.fulizhe.observability.mockoap.store.StoreAddResult;

/**
 * mock-OAP 接收到的日志进程内有界缓存。
 *
 * <p>与 {@link com.fulizhe.observability.mockoap.trace.TraceSegmentBuffer} 同形:写线程 = 消费线程,
 * 读线程 = 观测页;快照返回副本。日志**不去重**,只按容量 FIFO 淘汰最旧。
 *
 * <p>入库前先过 {@link MockOapLogs#isSelfLog}:采集器自身的日志一律拒收并计入
 * {@link #droppedSelfTotal()},否则"收到日志 -> 打日志 -> 再被采集"会无限放大。
 */
public class LogBuffer {

	private final BoundedFifoStore<MockOapLogRecord> store;

	/** traceId -> 当前缓存内条数。用于"这条链路有没有日志"的廉价判断(随淘汰同步维护)。 */
	private final Map<String, Integer> countByTraceId = new HashMap<String, Integer>();

	/** 让 store 淘汰与索引更新落在同一个临界区，读索引时不会看到两者之间的中间态。 */
	private final Object indexLock = new Object();

	/** 被"拒收自己"拦下的条数。 */
	private final AtomicLong droppedSelfTotal = new AtomicLong();

	public LogBuffer(MockOapProperties properties) {
		this.store = new BoundedFifoStore<MockOapLogRecord>(properties.getMaxLogs());
	}

	/**
	 * 写入一条日志;若是采集器自己的日志则拒收。
	 *
	 * @return 是否入库(true = 已接收,false = 被拒收/参数非法)
	 */
	public boolean add(MockOapLogRecord record) {
		if (record == null) {
			return false;
		}
		if (MockOapLogs.isSelfLog(record)) {
			droppedSelfTotal.incrementAndGet();
			return false;
		}
		synchronized (indexLock) {
			StoreAddResult<MockOapLogRecord> result = store.add(record);
			if (!result.isAccepted()) {
				return false;
			}
			if (result.evicted() != null) {
				indexRemove(result.evicted().getTraceId());
			}
			indexAdd(record.getTraceId());
			return true;
		}
	}

	/** 当前快照(副本),按接收顺序倒序(最新在前);{@code limit <= 0} 表示不限(受容量约束)。 */
	public List<MockOapLogRecord> snapshot(int limit) {
		return store.snapshot(limit);
	}

	/**
	 * 过滤查询,结果按最新在前。
	 *
	 * @param traceId 精确匹配的链路 id;空白表示不过滤
	 * @param level   精确匹配的级别(大小写不敏感);空白表示不过滤
	 * @param q       正文/服务名/端点的大小写不敏感子串;空白表示不过滤
	 * @param limit   最多返回条数;{@code <= 0} 表示不限(受容量约束)
	 */
	public List<MockOapLogRecord> query(String traceId, String level, String q, int limit) {
		String t = blankToNull(traceId);
		String l = blankToNull(level);
		String kw = blankToNull(q);
		int max = limit > 0 ? limit : Integer.MAX_VALUE;
		List<MockOapLogRecord> result = new ArrayList<MockOapLogRecord>();
		for (MockOapLogRecord record : store.snapshot(0)) {
			if (result.size() >= max) {
				break;
			}
			if (matches(record, t, l, kw)) {
				result.add(record);
			}
		}
		return result;
	}

	/** 某个 traceId 下的全部日志(最新在前),供链路页「日志」页签兜底用。 */
	public List<MockOapLogRecord> findByTraceId(String traceId) {
		return query(traceId, null, null, 0);
	}

	/**
	 * 某个 span 名下的日志(最新在前),供链路页把日志挂到具体 span 上。
	 *
	 * <p>判定:同一个 traceId + 同一个 traceSegmentId + 同一个 spanId。没带段 id 的日志
	 * (例如只带了 traceId)无法定位到具体 span,这里不返回;它们仍可在
	 * {@code /mock-oap/logs?traceId=} 看到(链路页「日志」页签也会兜底展示)。
	 */
	public List<MockOapLogRecord> findBySpan(String traceId, String traceSegmentId, int spanId) {
		String t = blankToNull(traceId);
		String seg = blankToNull(traceSegmentId);
		List<MockOapLogRecord> out = new ArrayList<MockOapLogRecord>();
		if (t == null || seg == null) {
			return out;
		}
		for (MockOapLogRecord record : store.snapshot(0)) {
			if (t.equals(record.getTraceId()) && seg.equals(record.getTraceSegmentId())
					&& record.getSpanId() == spanId) {
				out.add(record);
			}
		}
		return out;
	}

	/** 当前缓存内该 traceId 的日志条数(0 表示没有)。 */
	public int countByTraceId(String traceId) {
		if (traceId == null) {
			return 0;
		}
		synchronized (indexLock) {
			Integer n = countByTraceId.get(traceId);
			return n == null ? 0 : n;
		}
	}

	/** 当前缓存条数。 */
	public int size() {
		return store.size();
	}

	public int capacity() {
		return store.capacity();
	}

	public long receivedTotal() {
		return store.receivedTotal();
	}

	public long droppedByCapacity() {
		return store.droppedByCapacity();
	}

	public long droppedSelfTotal() {
		return droppedSelfTotal.get();
	}

	/** 便于状态口/页面展示的汇总。 */
	public Map<String, Object> stats() {
		Map<String, Object> stats = store.stats();
		stats.put("droppedSelfTotal", droppedSelfTotal());
		return stats;
	}

	private static boolean matches(MockOapLogRecord record, String traceId, String level, String q) {
		if (traceId != null && !traceId.equals(record.getTraceId())) {
			return false;
		}
		if (level != null && !level.equalsIgnoreCase(record.getLevel())) {
			return false;
		}
		if (q != null) {
			String kw = q.toLowerCase();
			if (!containsIgnoreCase(record.getContent(), kw) && !containsIgnoreCase(record.getService(), kw)
					&& !containsIgnoreCase(record.getEndpoint(), kw)) {
				return false;
			}
		}
		return true;
	}

	private static boolean containsIgnoreCase(String haystack, String lowerKeyword) {
		return haystack != null && haystack.toLowerCase().contains(lowerKeyword);
	}

	private static String blankToNull(String s) {
		return s == null || s.trim().isEmpty() ? null : s.trim();
	}

	/** 调用方必须持有 {@link #indexLock}。 */
	private void indexAdd(String traceId) {
		if (traceId == null || traceId.isEmpty()) {
			return;
		}
		Integer n = countByTraceId.get(traceId);
		countByTraceId.put(traceId, n == null ? 1 : n + 1);
	}

	/** 调用方必须持有 {@link #indexLock}。 */
	private void indexRemove(String traceId) {
		if (traceId == null || traceId.isEmpty()) {
			return;
		}
		Integer n = countByTraceId.get(traceId);
		if (n == null) {
			return;
		}
		if (n <= 1) {
			countByTraceId.remove(traceId);
		} else {
			countByTraceId.put(traceId, n - 1);
		}
	}
}
