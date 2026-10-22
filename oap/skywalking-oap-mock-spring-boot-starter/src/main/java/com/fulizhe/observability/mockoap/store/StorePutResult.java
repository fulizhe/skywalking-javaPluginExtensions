package com.fulizhe.observability.mockoap.store;

/** Result of inserting one keyed value into a bounded store. */
public final class StorePutResult<V> {

	private final boolean inserted;
	private final V previous;
	private final V evicted;

	public StorePutResult(boolean inserted, V previous, V evicted) {
		this.inserted = inserted;
		this.previous = previous;
		this.evicted = evicted;
	}

	public boolean isInserted() {
		return inserted;
	}

	public V previous() {
		return previous;
	}

	public V evicted() {
		return evicted;
	}
}
