package com.fulizhe.observability.mockoap.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

public class IngestPipelineTests {

	@Test
	public void closeProcessesAlreadyAcceptedItems() throws Exception {
		BlockingHandler handler = new BlockingHandler();
		IngestPipeline<String> pipeline = new IngestPipeline<String>("close-test", 4, 1024L, String::length, handler);
		pipeline.start();
		pipeline.submit("a");
		assertTrue(handler.entered.await(2, TimeUnit.SECONDS));
		pipeline.submit("b");

		Thread closer = new Thread(pipeline::close);
		closer.start();
		Thread.sleep(100L);
		handler.release.countDown();
		closer.join(3000L);

		assertTrue(!closer.isAlive());
		assertEquals(Arrays.asList("a", "b"), handler.processed);
	}

	@Test
	public void reportsEvictedItemsToTheEvictionListener() throws Exception {
		BlockingHandler handler = new BlockingHandler();
		List<String> evicted = new CopyOnWriteArrayList<String>();
		IngestPipeline<String> pipeline = new IngestPipeline<String>("evict-test", 1, 1024L, String::length,
				evicted::add, handler);
		pipeline.start();
		try {
			pipeline.submit("a");
			assertTrue(handler.entered.await(2, TimeUnit.SECONDS));
			pipeline.submit("b");
			assertEquals(IngestSubmitResult.ACCEPTED_AFTER_DROP, pipeline.submit("c"));
			assertEquals(Arrays.asList("b"), evicted);
		} finally {
			handler.release.countDown();
			pipeline.close();
		}
	}

	@Test
	public void submitDoesNotWaitForSlowHandler() throws Exception {
		BlockingHandler handler = new BlockingHandler();
		IngestPipeline<String> pipeline = new IngestPipeline<String>("test", 4, 1024L, String::length, handler);
		pipeline.start();
		try {
			assertEquals(IngestSubmitResult.ACCEPTED, pipeline.submit("a"));
			assertTrue(handler.entered.await(2, TimeUnit.SECONDS));
			assertEquals(IngestSubmitResult.ACCEPTED, pipeline.submit("b"));
			handler.release.countDown();
			assertTrue(pipeline.awaitIdle(2000L));
			assertEquals(2, handler.processed.size());
		} finally {
			pipeline.close();
		}
	}

	private static final class BlockingHandler implements Consumer<String> {
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final List<String> processed = new CopyOnWriteArrayList<String>();

		@Override
		public void accept(String value) {
			entered.countDown();
			try {
				release.await(2, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			processed.add(value);
		}
	}
}
