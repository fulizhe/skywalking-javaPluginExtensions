package org.apache.skywalking.apm.toolkit;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 环形阻塞队列（组合模式，线程安全）
 * 容量固定，当队列满时，插入新元素会自动删除队首最早元素。
 * 插入操作永远成功（不会阻塞或抛出异常），但删除操作（take/poll）可能等待或返回null。
 * <p> 参考 {@link https://github.com/apache/skywalking-java/blob/e0e8b3c8c304735991e057d431910ed1f4a57cdd/apm-sniffer/optional-reporter-plugins/kafka-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/kafka/KafkaJVMMetricsSender.java}
 * @param <E> 元素类型
 */
public class CircularBlockingQueue<E> implements BlockingQueue<E> {

	private final BlockingQueue<E> delegate; // 实际存储
	private final int capacity; // 固定容量
	private final ReentrantLock lock = new ReentrantLock(); // 保证复合操作的原子性

	public CircularBlockingQueue(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("Capacity must be positive");
		}
		this.capacity = capacity;
		this.delegate = new LinkedBlockingQueue<>(capacity);
	}

	// ========== 核心插入方法（带环形淘汰逻辑） ==========

	/**
	 * 插入元素（永远成功，队列满时自动淘汰队首元素）
	 */
	private boolean insert(E e) {
		if (e == null) {
			throw new NullPointerException();
		}
		lock.lock();
		try {
			if (delegate.size() >= capacity) {
				// 已满：移除队首最早元素
				delegate.poll();
			}
			// 此时一定有空位（或刚移除了一个），直接添加
			return delegate.add(e); // add 在有空位时返回 true
		} finally {
			lock.unlock();
		}
	}

	// ========== 实现 BlockingQueue 的所有插入方法 ==========

	@Override
	public void put(E e) throws InterruptedException {
		insert(e); // 永不阻塞，忽略中断
	}

	@Override
	public boolean offer(E e, long timeout, TimeUnit unit) {
		insert(e);
		return true;
	}

	@Override
	public boolean offer(E e) {
		insert(e);
		return true;
	}

	@Override
	public boolean add(E e) {
		insert(e);
		return true; // 总是成功，不抛 IllegalStateException
	}

	// ========== 删除/获取方法（直接委托） ==========

	@Override
	public E take() throws InterruptedException {
		return delegate.take();
	}

	@Override
	public E poll(long timeout, TimeUnit unit) throws InterruptedException {
		return delegate.poll(timeout, unit);
	}

	@Override
	public E poll() {
		return delegate.poll();
	}

	@Override
	public E remove() {
		return delegate.remove();
	}

	@Override
	public E element() {
		return delegate.element();
	}

	@Override
	public E peek() {
		return delegate.peek();
	}

	// ========== 查询方法 ==========

	@Override
	public int size() {
		return delegate.size();
	}

	@Override
	public int remainingCapacity() {
		// 虽然实际插入永远不会阻塞，但这里返回剩余容量（在不淘汰情况下的空位）
		return capacity - delegate.size();
	}

	@Override
	public boolean isEmpty() {
		return delegate.isEmpty();
	}

	@Override
	public boolean contains(Object o) {
		return delegate.contains(o);
	}

	@Override
	public int drainTo(Collection<? super E> c) {
		return delegate.drainTo(c);
	}

	@Override
	public int drainTo(Collection<? super E> c, int maxElements) {
		return delegate.drainTo(c, maxElements);
	}

	@Override
	public Object[] toArray() {
		return delegate.toArray();
	}

	@Override
	public <T> T[] toArray(T[] a) {
		return delegate.toArray(a);
	}

	// ========== 批量操作 ==========

	/**
	 * 批量添加：每个元素都会触发环形淘汰逻辑
	 */
	@Override
	public boolean addAll(Collection<? extends E> c) {
		if (c == null)
			throw new NullPointerException();
		boolean modified = false;
		for (E e : c) {
			if (add(e))
				modified = true;
		}
		return modified;
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		return delegate.removeAll(c);
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		return delegate.retainAll(c);
	}

	@Override
	public void clear() {
		delegate.clear();
	}

	@Override
	public boolean remove(Object o) {
		return delegate.remove(o);
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		return delegate.containsAll(c);
	}

	// ========== 迭代器 ==========

	@Override
	public Iterator<E> iterator() {
		return delegate.iterator();
	}
}