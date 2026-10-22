package com.fulizhe.observability.mockoap;

import java.util.function.Consumer;
import java.util.function.ToLongFunction;

import com.fulizhe.observability.mockoap.ingest.IngestPipeline;

/**
 * Creates and starts the intake pipelines from one place so every gRPC service uses the
 * same queue configuration and lifecycle.
 */
public final class MockOapPipelines {

	private MockOapPipelines() {
	}

	public static <T> IngestPipeline<T> start(String name, MockOapProperties properties,
			ToLongFunction<T> sizeOf, Consumer<T> handler) {
		return start(name, properties, sizeOf, null, handler);
	}

	public static <T> IngestPipeline<T> start(String name, MockOapProperties properties,
			ToLongFunction<T> sizeOf, Consumer<T> onEvicted, Consumer<T> handler) {
		IngestPipeline<T> pipeline = new IngestPipeline<T>(name, properties.getIngestQueueCapacity(),
				properties.getIngestQueueMaxBytes(), sizeOf, onEvicted, handler);
		pipeline.start();
		return pipeline;
	}
}
