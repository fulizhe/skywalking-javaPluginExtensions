package com.fulizhe.observability.mockoap.ingest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 有界 ingress 队列：提交方只做非阻塞的 offer，满了丢最旧；消费方在独立线程 take。
 *
 * <p>同时按条数和估算字节数限额，避免几条 16MB 的 protobuf 消息撑爆内存。
 */
public final class BoundedIngressQueue<T> {

	private static final Logger log = LoggerFactory.getLogger(BoundedIngressQueue.class);

	private final int maxItems;
	private final long maxBytes;
	private final ToLongFunction<T> sizeOf;
	private final ArrayDeque<Entry<T>> queue;
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition notEmpty = lock.newCondition();
	private final AtomicLong submittedTotal = new AtomicLong();
	private final AtomicLong droppedByCapacity = new AtomicLong();
	private final AtomicLong rejectedOversize = new AtomicLong();

	private long queuedBytes;
	private volatile boolean stopped;

	public BoundedIngressQueue(int maxItems, long maxBytes, ToLongFunction<T> sizeOf) {
		this.maxItems = Math.max(1, maxItems);
		this.maxBytes = Math.max(0L, maxBytes);
		this.sizeOf = sizeOf;
		this.queue = new ArrayDeque<Entry<T>>(Math.min(this.maxItems, 256));
	}

	/** 非阻塞提交。队列满时先丢最旧，再放入当前消息。 */
	public IngestSubmitResult submit(T value) {
		return submit(value, null);
	}

	/**
	 * 非阻塞提交。队列满时先丢最旧，再放入当前消息；被淘汰的旧消息在释放队列锁后
	 * 交给 {@code onEvicted}，便于调用方按业务单位累计丢失量。
	 */
	public IngestSubmitResult submit(T value, Consumer<T> onEvicted) {
		if (stopped) {
			return IngestSubmitResult.REJECTED_STOPPED;
		}
		if (value == null) {
			return IngestSubmitResult.REJECTED_INVALID;
		}
		long bytes = Math.max(0L, sizeOf.applyAsLong(value));
		if (maxBytes > 0 && bytes > maxBytes) {
			rejectedOversize.incrementAndGet();
			return IngestSubmitResult.REJECTED_OVERSIZE;
		}
		List<T> evictedValues = onEvicted == null ? null : new ArrayList<T>();
		IngestSubmitResult result;
		lock.lock();
		try {
			boolean dropped = false;
			Entry<T> entry = new Entry<T>(value, bytes);
			while (!queue.isEmpty() && (queue.size() >= maxItems
					|| (maxBytes > 0 && queuedBytes + bytes > maxBytes))) {
				Entry<T> oldest = queue.removeFirst();
				queuedBytes -= oldest.bytes;
				droppedByCapacity.incrementAndGet();
				if (evictedValues != null) {
					evictedValues.add(oldest.value);
				}
				dropped = true;
			}
			queue.addLast(entry);
			queuedBytes += bytes;
			submittedTotal.incrementAndGet();
			notEmpty.signal();
			result = dropped ? IngestSubmitResult.ACCEPTED_AFTER_DROP : IngestSubmitResult.ACCEPTED;
		} finally {
			lock.unlock();
		}
		if (evictedValues != null) {
			for (T evicted : evictedValues) {
				try {
					onEvicted.accept(evicted);
				} catch (RuntimeException e) {
					log.warn("### [mock-oap] ingress eviction listener failed", e);
				}
			}
		}
		return result;
	}

	/** 消费线程使用；队列为空时等待。 */
	public T take() throws InterruptedException {
		lock.lock();
		try {
			while (queue.isEmpty()) {
				if (stopped) {
					return null;
				}
				notEmpty.await();
			}
			return removeFirst().value;
		} finally {
			lock.unlock();
		}
	}

	public void stop() {
		lock.lock();
		try {
			stopped = true;
			notEmpty.signalAll();
		} finally {
			lock.unlock();
		}
	}

	public int size() {
		lock.lock();
		try {
			return queue.size();
		} finally {
			lock.unlock();
		}
	}

	public long queuedBytes() {
		lock.lock();
		try {
			return queuedBytes;
		} finally {
			lock.unlock();
		}
	}

	public int capacity() {
		return maxItems;
	}

	public long maxBytes() {
		return maxBytes;
	}

	public long submittedTotal() {
		return submittedTotal.get();
	}

	public long droppedByCapacity() {
		return droppedByCapacity.get();
	}

	public long rejectedOversize() {
		return rejectedOversize.get();
	}

	public Map<String, Object> stats() {
		Map<String, Object> stats = new LinkedHashMap<String, Object>();
		stats.put("queueDepth", size());
		stats.put("queueBytes", queuedBytes());
		stats.put("queueCapacity", capacity());
		stats.put("queueMaxBytes", maxBytes());
		stats.put("submittedTotal", submittedTotal());
		stats.put("droppedByCapacity", droppedByCapacity());
		stats.put("rejectedOversize", rejectedOversize());
		return stats;
	}

	private Entry<T> removeFirst() {
		Entry<T> entry = queue.removeFirst();
		queuedBytes -= entry.bytes;
		if (queuedBytes < 0L) {
			queuedBytes = 0L;
		}
		return entry;
	}

	private static final class Entry<T> {
		private final T value;
		private final long bytes;

		private Entry(T value, long bytes) {
			this.value = value;
			this.bytes = bytes;
		}
	}
}
