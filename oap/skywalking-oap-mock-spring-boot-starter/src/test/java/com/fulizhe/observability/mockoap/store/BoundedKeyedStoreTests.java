package com.fulizhe.observability.mockoap.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

public class BoundedKeyedStoreTests {

	@Test
	public void movesUpdatedKeyToNewestAndEvictsOldestOnInsert() {
		BoundedKeyedStore<String, String> store = new BoundedKeyedStore<String, String>(2);
		assertTrue(store.put("a", "A").isInserted());
		assertTrue(store.put("b", "B").isInserted());

		StorePutResult<String> updated = store.put("a", "A2");
		assertFalse(updated.isInserted());
		assertEquals("A", updated.previous());
		assertEquals(Arrays.asList("B", "A2"), values(store));
		assertEquals(3L, store.stats().get("receivedTotal"));

		StorePutResult<String> inserted = store.put("c", "C");
		assertTrue(inserted.isInserted());
		assertEquals("B", inserted.evicted());
		assertEquals(Arrays.asList("A2", "C"), values(store));
		assertEquals(4L, store.stats().get("receivedTotal"));
		assertEquals(1L, store.droppedByCapacity());
	}

	private static List<String> values(BoundedKeyedStore<String, String> store) {
		return new ArrayList<String>(store.snapshot(0));
	}
}
