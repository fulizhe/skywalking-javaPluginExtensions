package com.fulizhe.observability.mockoap.ingest;

import java.util.Map;

/**
 * Lifecycle surface shared by services that own an {@link IngestPipeline}.
 *
 * <p>Implementations expose their pipeline and inherit the close/idle/stats
 * forwarding so those three methods do not drift between services.
 */
public interface IngestLifecycle extends AutoCloseable {

	IngestPipeline<?> ingestPipeline();

	@Override
	default void close() {
		ingestPipeline().close();
	}

	default boolean awaitIdle(long timeoutMillis) throws InterruptedException {
		return ingestPipeline().awaitIdle(timeoutMillis);
	}

	default Map<String, Object> stats() {
		return ingestPipeline().stats();
	}
}
