package com.fulizhe.observability.mockoap.store;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 有界 keyed 存储：按 key 去重，重复写入会移到最新位置；超过容量淘汰最旧。
 *
 * <p>由 TraceSegmentBuffer 组合持有。容量按 key 数计算，具体记录内部的 span
 * 数量由调用方继续约束。
 */
public final class BoundedKeyedStore<K, V> {

	private final LinkedHashMap<K, V> store;
	private final Object lock = new Object();
	private final StoreMetrics metrics;

	public BoundedKeyedStore(int capacity) {
		this.metrics = new StoreMetrics(capacity);
		this.store = new LinkedHashMap<K, V>(Math.min(metrics.capacity(), 256), 0.75f, false);
	}

	public StorePutResult<V> put(K key, V value) {
		if (key == null || value == null) {
			return new StorePutResult<V>(false, null, null);
		}
		synchronized (lock) {
			return putLocked(key, value);
		}
	}

	public StorePutResult<V> compute(K key, BiFunction<K, V, V> remapping) {
		if (key == null) {
			return new StorePutResult<V>(false, null, null);
		}
		synchronized (lock) {
			V previous = store.get(key);
			V next = remapping.apply(key, previous);
			if (next == null) {
				if (previous != null) {
					store.remove(key);
				}
				return new StorePutResult<V>(false, previous, null);
			}
			return putLocked(key, next);
		}
	}

	private StorePutResult<V> putLocked(K key, V value) {
		V previous = store.remove(key);
		V evicted = null;
		if (previous == null && store.size() >= metrics.capacity()) {
			evicted = evictOldest();
		}
		store.put(key, value);
		metrics.recordReceived();
		return new StorePutResult<V>(previous == null, previous, evicted);
	}

	/** @return 按插入顺序返回的副本；limit<=0 表示不限。 */
	public List<V> snapshot(int limit) {
		int max = limit > 0 ? limit : Integer.MAX_VALUE;
		List<V> result = new ArrayList<V>();
		synchronized (lock) {
			for (V value : store.values()) {
				if (result.size() >= max) {
					break;
				}
				result.add(value);
			}
		}
		return result;
	}

	public int size() {
		synchronized (lock) {
			return store.size();
		}
	}

	public int capacity() {
		return metrics.capacity();
	}

	public long droppedByCapacity() {
		return metrics.droppedByCapacity();
	}

	public Map<String, Object> stats() {
		return metrics.stats(size());
	}

	public Map<String, Object> stats(String receivedTotalKey) {
		return metrics.stats(size(), receivedTotalKey);
	}

	private V evictOldest() {
		Iterator<Map.Entry<K, V>> it = store.entrySet().iterator();
		if (!it.hasNext()) {
			return null;
		}
		V evicted = it.next().getValue();
		it.remove();
		metrics.recordDropped();
		return evicted;
	}
}
