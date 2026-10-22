package com.fulizhe.observability.mockoap.ingest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单一消费者管线：接收线程只 submit，转换/入库/日志都在独立 worker 上执行。
 */
public final class IngestPipeline<T> implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(IngestPipeline.class);

	private final String name;
	private final BoundedIngressQueue<T> queue;
	private final Consumer<T> onEvicted;
	private final Consumer<T> handler;
	private final ExecutorService executor;
	private final AtomicLong processedTotal = new AtomicLong();
	private final AtomicLong failedTotal = new AtomicLong();
	private final AtomicInteger inFlight = new AtomicInteger();
	private final AtomicLong lastProcessedAt = new AtomicLong();
	private final AtomicLong lastDropWarnAt = new AtomicLong();

	private volatile boolean running;
	private volatile boolean waiting;

	public IngestPipeline(String name, int capacity, long maxBytes, ToLongFunction<T> sizeOf, Consumer<T> handler) {
		this(name, capacity, maxBytes, sizeOf, null, handler);
	}

	public IngestPipeline(String name, int capacity, long maxBytes, ToLongFunction<T> sizeOf, Consumer<T> onEvicted,
			Consumer<T> handler) {
		this.name = name;
		this.queue = new BoundedIngressQueue<T>(capacity, maxBytes, sizeOf);
		this.onEvicted = onEvicted;
		this.handler = handler;
		this.executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "mock-oap-" + name + "-worker");
			thread.setDaemon(true);
			return thread;
		});
	}

	public synchronized void start() {
		if (running) {
			return;
		}
		log.info("### [mock-oap] {} ingest starting: queueCapacity={}, queueMaxBytes={}",
				name, queue.capacity(), queue.maxBytes());
		running = true;
		try {
			executor.submit(this::loop);
		} catch (RuntimeException e) {
			running = false;
			log.error("### [mock-oap] {} ingest failed to start", name, e);
			throw e;
		}
	}

	public IngestSubmitResult submit(T value) {
		if (!running) {
			warnDrop("pipeline stopped");
			return IngestSubmitResult.REJECTED_STOPPED;
		}
		IngestSubmitResult result = queue.submit(value, onEvicted);
		if (result == IngestSubmitResult.ACCEPTED_AFTER_DROP) {
			warnDrop("queue full; dropped oldest");
		} else if (result.isRejected()) {
			warnDrop(rejectionReason(result));
		}
		return result;
	}

	/** 供测试/关闭前等待当前队列处理完。 */
	public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
		while (System.currentTimeMillis() <= deadline) {
			if (queue.size() == 0 && inFlight.get() == 0 && waiting) {
				return true;
			}
			Thread.sleep(5L);
		}
		return queue.size() == 0 && inFlight.get() == 0 && waiting;
	}

	public boolean isRunning() {
		return running;
	}

	public String name() {
		return name;
	}

	public Map<String, Object> stats() {
		Map<String, Object> stats = new LinkedHashMap<String, Object>(queue.stats());
		stats.put("name", name);
		stats.put("running", running);
		stats.put("processedTotal", processedTotal.get());
		stats.put("failedTotal", failedTotal.get());
		stats.put("inFlight", inFlight.get());
		stats.put("lastProcessedAt", lastProcessedAt.get());
		return stats;
	}

	@Override
	public synchronized void close() {
		if (!running) {
			return;
		}
		log.info("### [mock-oap] {} ingest closing: queueDepth={}, processed={}, failed={}, dropped={}",
				name, queue.size(), processedTotal.get(), failedTotal.get(), queue.droppedByCapacity());
		running = false;
		queue.stop();
		executor.shutdown();
		try {
			if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
				log.warn("### [mock-oap] {} ingest did not drain within 2s; forcing shutdown: queueDepth={}",
						name, queue.size());
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
			log.warn("### [mock-oap] {} ingest close interrupted; worker forced down", name);
		}
		log.info("### [mock-oap] {} ingest closed: queueDepth={}, processed={}, failed={}, dropped={}",
				name, queue.size(), processedTotal.get(), failedTotal.get(), queue.droppedByCapacity());
	}

	private void loop() {
		log.info("### [mock-oap] {} ingest worker started: thread={}", name, Thread.currentThread().getName());
		try {
			while (running || queue.size() > 0) {
				T value;
				waiting = true;
				try {
					value = queue.take();
				} catch (InterruptedException e) {
					if (!running) {
						return;
					}
					continue;
				} finally {
					waiting = false;
				}
				if (value == null) {
					return;
				}
				process(value);
			}
		} catch (Throwable t) {
			running = false;
			log.error("### [mock-oap] {} ingest worker died unexpectedly", name, t);
		} finally {
			log.info("### [mock-oap] {} ingest worker exited: queueDepth={}, processed={}, failed={}",
					name, queue.size(), processedTotal.get(), failedTotal.get());
		}
	}

	private static String rejectionReason(IngestSubmitResult result) {
		if (result == IngestSubmitResult.REJECTED_INVALID) {
			return "message is null";
		}
		if (result == IngestSubmitResult.REJECTED_OVERSIZE) {
			return "message exceeds queue byte limit";
		}
		return "queue stopped";
	}

	private void warnDrop(String reason) {
		long now = System.currentTimeMillis();
		long last = lastDropWarnAt.get();
		if (now - last < 30_000L || !lastDropWarnAt.compareAndSet(last, now)) {
			return;
		}
		log.warn("### [mock-oap] {} ingest {}; queueDepth={}, queueCapacity={}, dropped={}, rejectedOversize={}",
				name, reason, queue.size(), queue.capacity(), queue.droppedByCapacity(), queue.rejectedOversize());
	}

	private void process(T value) {
		inFlight.incrementAndGet();
		try {
			handler.accept(value);
			processedTotal.incrementAndGet();
			lastProcessedAt.set(System.currentTimeMillis());
		} catch (Throwable t) {
			failedTotal.incrementAndGet();
			log.warn("### [mock-oap] {} ingest failed: {}", name, t.toString());
		} finally {
			inFlight.decrementAndGet();
		}
	}
}
