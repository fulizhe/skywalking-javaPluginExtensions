package com.fulizhe.observability.mockoap.store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 有界 Store 的容量与计数。由具体 Store 组合持有，不做继承。
 */
public final class StoreMetrics {

	private final int capacity;
	private final AtomicLong receivedTotal = new AtomicLong();
	private final AtomicLong droppedByCapacity = new AtomicLong();

	public StoreMetrics(int capacity) {
		this.capacity = Math.max(1, capacity);
	}

	public int capacity() {
		return capacity;
	}

	public long receivedTotal() {
		return receivedTotal.get();
	}

	public long droppedByCapacity() {
		return droppedByCapacity.get();
	}

	public void recordReceived() {
		receivedTotal.incrementAndGet();
	}

	public void recordDropped() {
		droppedByCapacity.incrementAndGet();
	}

	public Map<String, Object> stats(int size) {
		return stats(size, "receivedTotal");
	}

	public Map<String, Object> stats(int size, String receivedTotalKey) {
		Map<String, Object> stats = new LinkedHashMap<String, Object>();
		stats.put("size", size);
		stats.put("capacity", capacity);
		stats.put(receivedTotalKey, receivedTotal());
		stats.put("droppedByCapacity", droppedByCapacity());
		return stats;
	}
}
