package org.apache.skywalking.apm.agent.core.reporter.logfile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class KeyedLocalStoreTest {

	@Test
	public void putReturnsPreviousAndEvictsFifoWhenFull() {
		final KeyedLocalStore<String, String> store = new KeyedLocalStore<String, String>(2);
		assertNull(store.put("a", "1"));
		assertNull(store.put("b", "2"));
		assertEquals("1", store.put("a", "1-updated"));
		store.put("c", "3");
		assertEquals(2, store.size());
		assertEquals(Arrays.<String>asList("b", "c"), new ArrayList<String>(store.snapshot().keySet()));
	}

	@Test
	public void mergeReturnsValueForNewKeyAndMergedValueForExisting() {
		final KeyedLocalStore<String, String> store = new KeyedLocalStore<String, String>(4);
		assertEquals("v1", store.merge("k", "v1", (a, b) -> a + "+" + b));
		assertEquals("v1+v2", store.merge("k", "v2", (a, b) -> a + "+" + b));
		assertEquals("v1+v2+v3", store.merge("k", "v3", (a, b) -> a + "+" + b));
		assertEquals(1, store.size());
	}

	@Test
	public void mergeWithNewKeyAtCapacityEvictsOldest() {
		final KeyedLocalStore<String, String> store = new KeyedLocalStore<String, String>(2);
		store.put("a", "1");
		store.put("b", "2");
		assertEquals("3", store.merge("c", "3", (x, y) -> y));
		assertEquals(Arrays.<String>asList("b", "c"), new ArrayList<String>(store.snapshot().keySet()));
	}

	@Test
	public void mergeRemovesKeyWhenRemapperReturnsNull() {
		final KeyedLocalStore<String, String> store = new KeyedLocalStore<String, String>(4);
		store.put("k", "v1");
		assertNull(store.merge("k", "v2", (a, b) -> null));
		assertEquals(0, store.size());
		assertTrue(store.snapshot().isEmpty());
	}

	@Test
	public void snapshotIsIndependentContainer() {
		final KeyedLocalStore<String, String> store = new KeyedLocalStore<String, String>(4);
		store.put("a", "1");
		final Map<String, String> snapshot = store.snapshot();
		assertEquals("1", snapshot.get("a"));
		store.put("b", "2");
		snapshot.put("a", "9");
		assertEquals(1, snapshot.size());
		assertEquals(2, store.size());
		assertEquals("1", store.snapshot().get("a"));
	}

	@Test
	public void snapshotEmptyStoreIsEmptyMap() {
		final KeyedLocalStore<String, String> store = new KeyedLocalStore<String, String>(4);
		assertTrue(store.snapshot().isEmpty());
		assertEquals(0, store.size());
	}

	@Test
	public void concurrentPutMergeSnapshotStaysBounded() throws InterruptedException {
		final KeyedLocalStore<String, String> store = new KeyedLocalStore<String, String>(16);
		final int threads = 4;
		final int perThread = 1000;
		final ExecutorService pool = Executors.newFixedThreadPool(threads);
		final CountDownLatch start = new CountDownLatch(1);
		try {
			for (int t = 0; t < threads; t++) {
				final int base = t;
				pool.execute(() -> {
					try {
						start.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
					for (int i = 0; i < perThread; i++) {
						final String key = "k-" + (base * perThread + i) % 16;
						store.put(key, "v");
						store.merge(key, "v", (a, b) -> a + "+" + b);
					}
				});
			}
			start.countDown();
			for (int i = 0; i < 200; i++) {
				store.snapshot();
				assertTrue(store.size() <= 16);
			}
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
	}
}
