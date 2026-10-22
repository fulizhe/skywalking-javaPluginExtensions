package com.fulizhe.observability.mockoap.jvmmetrics;

import java.util.List;
import java.util.Map;

import com.fulizhe.observability.mockoap.MockOapProperties;
import com.fulizhe.observability.mockoap.store.BoundedFifoStore;

/** mock-OAP 接收到的 JVM 指标批次进程内有界 FIFO 缓存。 */
public class JVMMetricBuffer {

	private final BoundedFifoStore<MockOapJvmMetricRecord> store;

	public JVMMetricBuffer(MockOapProperties properties) {
		this.store = new BoundedFifoStore<MockOapJvmMetricRecord>(properties.getMaxJvmMetrics());
	}

	/** 写入一个批次;容量超限时淘汰最旧批次。 */
	public void add(MockOapJvmMetricRecord record) {
		store.add(record);
	}

	/** 返回最新在前的副本; {@code limit <= 0} 表示返回全部缓存。 */
	public List<MockOapJvmMetricRecord> snapshot(int limit) {
		return store.snapshot(limit);
	}

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

	public Map<String, Object> stats() {
		return store.stats();
	}
}
