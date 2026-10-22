package com.fulizhe.observability.mockoap.jvmmetrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fulizhe.observability.mockoap.MockOapProperties;

public class JVMMetricBufferTests {

	@Test
	public void evictsOldestBatchWhenCapacityIsExceeded() {
		MockOapProperties properties = new MockOapProperties();
		properties.setMaxJvmMetrics(2);
		JVMMetricBuffer buffer = new JVMMetricBuffer(properties);

		buffer.add(record("one"));
		buffer.add(record("two"));
		buffer.add(record("three"));

		assertEquals(2, buffer.size());
		assertEquals(2, buffer.capacity());
		assertEquals(3L, buffer.receivedTotal());
		assertEquals("three", buffer.snapshot(1).get(0).getService());
		assertEquals("two", buffer.snapshot(0).get(1).getService());
		assertEquals(2, buffer.stats().get("size"));
		assertTrue(buffer.snapshot(2).stream().noneMatch(r -> "one".equals(r.getService())));
	}

	private static MockOapJvmMetricRecord record(String service) {
		MockOapJvmMetricRecord record = new MockOapJvmMetricRecord();
		record.setService(service);
		return record;
	}
}
