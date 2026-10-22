package com.fulizhe.observability.mockoap.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public class BoundedIngressQueueTests {

	@Test
	public void dropsOldestWhenCountCapacityIsFull() throws Exception {
		BoundedIngressQueue<String> queue = new BoundedIngressQueue<String>(2, 100L, String::length);
		assertEquals(IngestSubmitResult.ACCEPTED, queue.submit("aa"));
		assertEquals(IngestSubmitResult.ACCEPTED, queue.submit("bb"));
		assertEquals(IngestSubmitResult.ACCEPTED_AFTER_DROP, queue.submit("cc"));

		assertEquals(2, queue.size());
		assertEquals(1L, queue.droppedByCapacity());
		assertEquals("bb", queue.take());
		assertEquals("cc", queue.take());
	}

	@Test
	public void reportsEveryEvictedItemToTheCaller() {
		BoundedIngressQueue<String> queue = new BoundedIngressQueue<String>(2, 100L, String::length);
		List<String> evicted = new ArrayList<String>();
		queue.submit("aa");
		queue.submit("bb");

		assertEquals(IngestSubmitResult.ACCEPTED_AFTER_DROP, queue.submit("cc", evicted::add));
		assertEquals(Arrays.asList("aa"), evicted);
		assertEquals(1L, queue.droppedByCapacity());
	}

	@Test
	public void computesSizeOncePerSubmittedItemAndNeverOnTake() throws Exception {
		AtomicInteger sizeCalls = new AtomicInteger();
		BoundedIngressQueue<String> queue = new BoundedIngressQueue<String>(1, 100L, value -> {
			sizeCalls.incrementAndGet();
			return value.length();
		});

		queue.submit("aa");
		queue.submit("bb");
		assertEquals("bb", queue.take());

		assertEquals(2, sizeCalls.get());
	}

	@Test
	public void rejectsNullAsInvalidRatherThanOversize() {
		BoundedIngressQueue<String> queue = new BoundedIngressQueue<String>(2, 100L, String::length);
		assertEquals(IngestSubmitResult.REJECTED_INVALID, queue.submit(null));
		assertEquals(0, queue.size());
		assertEquals(0L, queue.rejectedOversize());
	}

	@Test
	public void rejectsOversizeWithoutEvictingExistingItems() {
		BoundedIngressQueue<String> queue = new BoundedIngressQueue<String>(2, 4L, String::length);
		assertEquals(IngestSubmitResult.ACCEPTED, queue.submit("aa"));
		assertEquals(IngestSubmitResult.REJECTED_OVERSIZE, queue.submit("12345"));

		assertEquals(1, queue.size());
		assertEquals(1L, queue.rejectedOversize());
	}
}
