package com.fulizhe.observability.mockoap.store;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 有界 FIFO 存储：写入时超过容量淘汰最旧一条。
 *
 * <p>由 LogBuffer/JVMMetricBuffer 组合持有。写锁只覆盖队列操作，不覆盖领域转换。
 */
public final class BoundedFifoStore<T> {

	private final ArrayDeque<T> store;
	private final Object lock = new Object();
	private final StoreMetrics metrics;

	public BoundedFifoStore(int capacity) {
		this.metrics = new StoreMetrics(capacity);
		this.store = new ArrayDeque<T>(Math.min(metrics.capacity(), 256));
	}

	public StoreAddResult<T> add(T value) {
		if (value == null) {
			return StoreAddResult.rejected();
		}
		synchronized (lock) {
			T evicted = null;
			if (store.size() >= metrics.capacity()) {
				evicted = store.removeFirst();
				metrics.recordDropped();
			}
			store.addLast(value);
			metrics.recordReceived();
			return StoreAddResult.accepted(evicted);
		}
	}

	/** @return 最新在前的副本。 */
	public List<T> snapshot(int limit) {
		int max = limit > 0 ? limit : Integer.MAX_VALUE;
		List<T> result = new ArrayList<T>();
		synchronized (lock) {
			Iterator<T> it = store.descendingIterator();
			while (it.hasNext() && result.size() < max) {
				result.add(it.next());
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

	public long receivedTotal() {
		return metrics.receivedTotal();
	}

	public long droppedByCapacity() {
		return metrics.droppedByCapacity();
	}

	public Map<String, Object> stats() {
		return metrics.stats(size());
	}
}
