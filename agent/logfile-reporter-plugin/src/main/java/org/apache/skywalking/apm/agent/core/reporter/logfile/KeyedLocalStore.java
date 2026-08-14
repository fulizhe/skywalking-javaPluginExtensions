package org.apache.skywalking.apm.agent.core.reporter.logfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 通用有界键式数据存储：按 key 存值，容量满时按 FIFO（最旧插入先出）淘汰。
 * <p>
 * 与追加式 {@code CircularBlockingQueue} 语义并行存在但不强行统一接口：
 * 写操作（{@code put}/{@code merge}）与快照读（{@code snapshot}/{@code size}）由单锁守护，
 * 保证同一 key 的并发合并不丢数据。{@code snapshot} 返回独立的容器拷贝，调用方增删该拷贝
 * 不影响存储内部状态；值对象本身按引用共享，由调用方以"只替换不原地修改"的方式发布
 * （如 merge 总是返回新值而非修改已有值），保证快照中的值不再被后续写入触及。
 * </p>
 *
 * @param <K> key 类型
 * @param <V> value 类型
 */
final class KeyedLocalStore<K, V> {

	private final int maxSize;
	private final LinkedHashMap<K, V> entries;
	private final ReentrantLock lock = new ReentrantLock();

	KeyedLocalStore(final int maxSize) {
		if (maxSize <= 0) {
			throw new IllegalArgumentException("Max size must be positive");
		}
		this.maxSize = maxSize;
		this.entries = new LinkedHashMap<K, V>(16, 0.75f, false) {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(final Map.Entry<K, V> eldest) {
				return size() > maxSize;
			}
		};
	}

	/**
	 * 写入 key，返回该 key 上一次的值或 {@code null}；新 key 超过容量时淘汰最旧插入项。
	 */
	V put(final K key, final V value) {
		Objects.requireNonNull(key, "key");
		lock.lock();
		try {
			return entries.put(key, value);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * 原子读改写合并：key 已存在时应用 {@code remapper}（入参为已有值与新值）并返回合并结果，
	 * 否则直接存 {@code value} 并返回。remapper 返回 {@code null} 时移除该 key（与
	 * {@code ConcurrentHashMap.merge} 语义对齐）。锁内执行，同一 key 的并发合并不会丢数据。
	 */
	V merge(final K key, final V value, final BiFunction<V, V, V> remapper) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(remapper, "remapper");
		lock.lock();
		try {
			final V existing = entries.get(key);
			if (existing == null) {
				entries.put(key, value);
				return value;
			}
			final V merged = remapper.apply(existing, value);
			if (merged == null) {
				entries.remove(key);
				return null;
			}
			entries.put(key, merged);
			return merged;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * 返回当前内容的独立结构副本 {@code Map}（插入序保持 FIFO 序）：
	 * 调用方增删该副本不影响存储内部状态；写操作与快照读由同一把锁互相排斥。
	 */
	Map<K, V> snapshot() {
		lock.lock();
		try {
			return new LinkedHashMap<K, V>(entries);
		} finally {
			lock.unlock();
		}
	}

	int size() {
		lock.lock();
		try {
			return entries.size();
		} finally {
			lock.unlock();
		}
	}
}
