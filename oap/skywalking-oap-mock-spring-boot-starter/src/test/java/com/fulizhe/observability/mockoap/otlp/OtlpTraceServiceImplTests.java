package com.fulizhe.observability.mockoap.otlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fulizhe.observability.mockoap.MockOapProperties;
import com.fulizhe.observability.mockoap.ingest.IngestSubmitResult;
import com.fulizhe.observability.mockoap.trace.MockOapTraceRecord;
import com.fulizhe.observability.mockoap.trace.TraceSegmentBuffer;
import com.google.protobuf.ByteString;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;

public class OtlpTraceServiceImplTests {

	@Test
	public void submitDoesNotValidateOnTheCallbackThread() throws Exception {
		MockOapProperties properties = new MockOapProperties();
		OtlpTraceServiceImpl service = new OtlpTraceServiceImpl(new TraceSegmentBuffer(properties),
				new OtlpTraceConverter(properties), properties);
		try {
			assertEquals(IngestSubmitResult.ACCEPTED, service.submit(invalidRequest()));
			assertTrue(service.awaitIdle(2000L));
			assertEquals(1L, service.rejectedSpanTotal());
		} finally {
			service.close();
		}
	}

	@Test
	public void countsSpansLostWhenQueueEvictsAnOlderRequest() throws Exception {
		MockOapProperties properties = new MockOapProperties();
		properties.setIngestQueueCapacity(1);
		properties.setMaxOtlpSpansPerRequest(10);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		OtlpTraceConverter slowConverter = new OtlpTraceConverter(properties) {
			@Override
			public List<MockOapTraceRecord> toRecords(ResourceSpans resourceSpans) {
				entered.countDown();
				try {
					release.await(2, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return super.toRecords(resourceSpans);
			}
		};
		OtlpTraceServiceImpl service = new OtlpTraceServiceImpl(new TraceSegmentBuffer(properties), slowConverter,
				properties);
		try {
			assertEquals(IngestSubmitResult.ACCEPTED, service.submit(validRequest()));
			assertTrue(entered.await(2, TimeUnit.SECONDS));
			assertEquals(IngestSubmitResult.ACCEPTED, service.submit(validRequest()));
			assertEquals(IngestSubmitResult.ACCEPTED_AFTER_DROP, service.submit(validRequest()));

			assertEquals(1L, service.queueEvictedSpanTotal());
			assertEquals(1L, service.lostSpanTotal());
		} finally {
			release.countDown();
			service.close();
		}
	}

	private static ExportTraceServiceRequest validRequest() {
		Span span = Span.newBuilder()
				.setTraceId(ByteString.copyFrom(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }))
				.setSpanId(ByteString.copyFrom(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }))
				.setName("valid")
				.setStartTimeUnixNano(1_700_000_000_000_000_000L)
				.setEndTimeUnixNano(1_700_000_000_100_000_000L)
				.build();
		return request(span);
	}

	private static ExportTraceServiceRequest invalidRequest() {
		Span span = Span.newBuilder()
				.setTraceId(ByteString.copyFrom(new byte[16]))
				.setSpanId(ByteString.copyFrom(new byte[] { 0, 0, 0, 0, 0, 0, 0, 1 }))
				.setName("invalid")
				.build();
		return request(span);
	}

	private static ExportTraceServiceRequest request(Span span) {
		ResourceSpans resourceSpans = ResourceSpans.newBuilder()
				.setResource(Resource.newBuilder()
						.addAttributes(KeyValue.newBuilder().setKey("service.name")
								.setValue(AnyValue.newBuilder().setStringValue("otlp-service"))))
				.addScopeSpans(ScopeSpans.newBuilder().addSpans(span))
				.build();
		return ExportTraceServiceRequest.newBuilder().addResourceSpans(resourceSpans).build();
	}
}
