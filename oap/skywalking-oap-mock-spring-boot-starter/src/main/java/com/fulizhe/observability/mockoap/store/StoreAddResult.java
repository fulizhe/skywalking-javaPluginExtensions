package com.fulizhe.observability.mockoap.store;

/** Result of adding one value to a FIFO store. */
public final class StoreAddResult<T> {

	private final boolean accepted;
	private final T evicted;

	private StoreAddResult(boolean accepted, T evicted) {
		this.accepted = accepted;
		this.evicted = evicted;
	}

	public static <T> StoreAddResult<T> accepted(T evicted) {
		return new StoreAddResult<T>(true, evicted);
	}

	public static <T> StoreAddResult<T> rejected() {
		return new StoreAddResult<T>(false, null);
	}

	public boolean isAccepted() {
		return accepted;
	}

	public T evicted() {
		return evicted;
	}
}
