package com.fulizhe.observability.mockoap.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

public class BoundedFifoStoreTests {

	@Test
	public void evictsOldestAndReportsCounters() {
		BoundedFifoStore<String> store = new BoundedFifoStore<String>(2);
		assertNull(store.add("a").evicted());
		assertNull(store.add("b").evicted());
		StoreAddResult<String> third = store.add("c");

		assertEquals("a", third.evicted());
		assertEquals(2, store.size());
		assertEquals(2, store.capacity());
		assertEquals(3L, store.receivedTotal());
		assertEquals(1L, store.droppedByCapacity());
		assertEquals(Arrays.asList("c", "b"), store.snapshot(0));
	}
}
