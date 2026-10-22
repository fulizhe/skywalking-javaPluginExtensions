package com.fulizhe.observability.mockoap.otlp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.fulizhe.observability.mockoap.MockOapPipelines;
import com.fulizhe.observability.mockoap.MockOapProperties;
import com.fulizhe.observability.mockoap.ingest.IngestLifecycle;
import com.fulizhe.observability.mockoap.ingest.IngestPipeline;
import com.fulizhe.observability.mockoap.ingest.IngestSubmitResult;
import com.fulizhe.observability.mockoap.trace.MockOapSpan;
import com.fulizhe.observability.mockoap.trace.MockOapTraceRecord;
import com.fulizhe.observability.mockoap.trace.TraceSegmentBuffer;

import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;

/** Handles OTLP TraceService/Export payloads and writes them into the shared trace buffer. */
public class OtlpTraceServiceImpl implements IngestLifecycle {

	private final TraceSegmentBuffer buffer;
	private final OtlpTraceConverter converter;
	private final MockOapProperties properties;
	private final AtomicLong rejectedSpanTotal = new AtomicLong();
	private final AtomicLong queueEvictedSpanTotal = new AtomicLong();
	private final AtomicLong acceptedSpanTotal = new AtomicLong();
	private final IngestPipeline<ExportTraceServiceRequest> pipeline;

	public OtlpTraceServiceImpl(TraceSegmentBuffer buffer, OtlpTraceConverter converter,
			MockOapProperties properties) {
		this.buffer = buffer;
		this.converter = converter;
		this.properties = properties;
		this.pipeline = MockOapPipelines.start("otlp", properties, ExportTraceServiceRequest::getSerializedSize,
				this::recordQueueEviction, this::export);
	}

	public OtlpTraceServiceImpl(TraceSegmentBuffer buffer, OtlpTraceConverter converter) {
		this(buffer, converter, new MockOapProperties());
	}

	/** 同步路径，供单测和直接调用；会立即完成转换和合并。 */
	public ExportTraceServiceResponse export(ExportTraceServiceRequest request) {
		PreparedRequest prepared = prepare(request);
		apply(prepared);
		return prepared.response;
	}

	/**
	 * gRPC 路径只做非阻塞入队；校验、限额、转换和合并全部在 worker 上执行。
	 * 因此响应是 best-effort：队列接受即返回成功，队列满则返回 RESOURCE_EXHAUSTED。
	 */
	public IngestSubmitResult submit(ExportTraceServiceRequest request) {
		if (request == null) {
			return IngestSubmitResult.REJECTED_INVALID;
		}
		IngestSubmitResult submitted = pipeline.submit(request);
		if (submitted.isRejected()) {
			rejectedSpanTotal.addAndGet(countSpans(request));
		}
		return submitted;
	}

	/** Queue eviction drops a whole request before its spans are validated; count them explicitly. */
	private void recordQueueEviction(ExportTraceServiceRequest request) {
		queueEvictedSpanTotal.addAndGet(countSpans(request));
	}

	private PreparedRequest prepare(ExportTraceServiceRequest request) {
		long rejected = 0;
		String error = null;
		long remaining = Math.max(0, properties.getMaxOtlpSpansPerRequest());
		long accepted = 0;
		List<PreparedResource> resources = new ArrayList<PreparedResource>();
		for (io.opentelemetry.proto.trace.v1.ResourceSpans resourceSpans : request.getResourceSpansList()) {
			long resourceSpanCount = countSpans(resourceSpans);
			boolean resourceValid = converter.isWithinStringLimit(resourceSpans)
					&& converter.hasUsableServiceName(resourceSpans);
			long validSpanCount = 0;
			if (resourceValid) {
				for (io.opentelemetry.proto.trace.v1.ScopeSpans scope : resourceSpans.getScopeSpansList()) {
					for (io.opentelemetry.proto.trace.v1.Span span : scope.getSpansList()) {
						if (converter.isValidSpan(span)) {
							validSpanCount++;
						}
					}
				}
			}
			long acceptedForResource = Math.min(validSpanCount, remaining);
			long rejectedInResource = resourceSpanCount - acceptedForResource;
			resources.add(new PreparedResource(resourceSpans, acceptedForResource));
			rejected += rejectedInResource;
			accepted += acceptedForResource;
			remaining = Math.max(0, remaining - acceptedForResource);
			if (rejectedInResource > 0 && error == null) {
				error = "Invalid or over-limit OTLP spans were rejected";
			}
		}
		ExportTraceServiceResponse response = rejected == 0
				? ExportTraceServiceResponse.getDefaultInstance()
				: ExportTraceServiceResponse.newBuilder().setPartialSuccess(ExportTracePartialSuccess.newBuilder()
						.setRejectedSpans(rejected)
						.setErrorMessage(error == null ? "OTLP spans were rejected" : error)).build();
		return new PreparedRequest(resources, accepted, rejected, response);
	}

	private void apply(PreparedRequest prepared) {
		for (PreparedResource resource : prepared.resources) {
			long budget = resource.accepted;
			if (budget <= 0) {
				continue;
			}
			for (MockOapTraceRecord incoming : converter.toRecords(resource.resourceSpans)) {
				List<MockOapSpan> accepted = new ArrayList<MockOapSpan>();
				for (MockOapSpan span : incoming.getSpans()) {
					if (budget > 0) {
						accepted.add(span);
						budget--;
					}
				}
				incoming.setSpans(accepted);
				if (!accepted.isEmpty()) {
					buffer.mergeOtlp(incoming);
				}
			}
		}
		rejectedSpanTotal.addAndGet(prepared.rejected);
		acceptedSpanTotal.addAndGet(prepared.accepted);
	}

	private static long countSpans(ExportTraceServiceRequest request) {
		long count = 0;
		for (io.opentelemetry.proto.trace.v1.ResourceSpans resourceSpans : request.getResourceSpansList()) {
			count += countSpans(resourceSpans);
		}
		return count;
	}

	@Override
	public IngestPipeline<ExportTraceServiceRequest> ingestPipeline() {
		return pipeline;
	}

	@Override
	public Map<String, Object> stats() {
		Map<String, Object> stats = IngestLifecycle.super.stats();
		stats.put("otlpAcceptedSpans", acceptedSpanTotal());
		stats.put("otlpRejectedSpans", rejectedSpanTotal());
		stats.put("otlpQueueEvictedSpans", queueEvictedSpanTotal());
		stats.put("otlpLostSpans", lostSpanTotal());
		return stats;
	}

	public long rejectedSpanTotal() { return rejectedSpanTotal.get(); }
	public long queueEvictedSpanTotal() { return queueEvictedSpanTotal.get(); }
	public long lostSpanTotal() { return rejectedSpanTotal.get() + queueEvictedSpanTotal.get(); }
	public long acceptedSpanTotal() { return acceptedSpanTotal.get(); }
	public long acceptedRecordTotal() { return buffer.otlpReceivedRecords(); }

	private static long countSpans(io.opentelemetry.proto.trace.v1.ResourceSpans resourceSpans) {
		long count = 0;
		for (io.opentelemetry.proto.trace.v1.ScopeSpans scope : resourceSpans.getScopeSpansList()) {
			count += scope.getSpansCount();
		}
		return count;
	}

	private static final class PreparedRequest {
		private final List<PreparedResource> resources;
		private final long accepted;
		private final long rejected;
		private final ExportTraceServiceResponse response;

		private PreparedRequest(List<PreparedResource> resources, long accepted, long rejected,
				ExportTraceServiceResponse response) {
			this.resources = resources;
			this.accepted = accepted;
			this.rejected = rejected;
			this.response = response;
		}
	}

	private static final class PreparedResource {
		private final io.opentelemetry.proto.trace.v1.ResourceSpans resourceSpans;
		private final long accepted;

		private PreparedResource(io.opentelemetry.proto.trace.v1.ResourceSpans resourceSpans, long accepted) {
			this.resourceSpans = resourceSpans;
			this.accepted = accepted;
		}
	}
}
