package com.fulizhe.observability.mockoap.trace;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.skywalking.apm.dependencies.io.grpc.stub.StreamObserver;
import org.apache.skywalking.apm.network.common.v3.Commands;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.language.agent.v3.RefType;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentReference;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;
import org.apache.skywalking.apm.network.language.agent.v3.TraceSegmentReportServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fulizhe.observability.mockoap.MockOapPipelines;
import com.fulizhe.observability.mockoap.MockOapProperties;
import com.fulizhe.observability.mockoap.ingest.IngestLifecycle;
import com.fulizhe.observability.mockoap.ingest.IngestPipeline;

/**
 * SkyWalking 原生协议 {@code TraceSegmentReportService.collect}(客户端流)的服务端实现。
 *
 * <p>先把 trace 这条路打通:只落链路段(含 span 明细与跨进程引用),JVM/Meter/Log/心跳等
 * 其它服务后续按需加。收到段后写入 {@link TraceSegmentBuffer},并以空 {@link Commands} 应答
 * (表示无命令下发,agent 侧无异常)。
 */
public class TraceSegmentReportServiceImpl
		extends TraceSegmentReportServiceGrpc.TraceSegmentReportServiceImplBase implements IngestLifecycle {

	private static final Logger log = LoggerFactory.getLogger(TraceSegmentReportServiceImpl.class);

	/** 单个 tag 值截断上限(防大字段撑爆内存)。 */
	private static final int MAX_TAG_VALUE_LEN = 256;

	/** 自激检测:进程内 mock-OAP 自己的 gRPC 服务被 agent 埋点后,span 的 operationName 前缀。 */
	private static final String SELF_TRACE_OP_PREFIX = "skywalking.v3.TraceSegmentReportService";

	/** 自激告警节流(30s 一次),避免循环时把日志刷爆。 */
	private static final long SELF_TRACE_WARN_INTERVAL_MS = 30_000L;

	private final AtomicLong lastSelfTraceWarnAt = new AtomicLong(0L);

	private final TraceSegmentBuffer buffer;
	private final IngestPipeline<SegmentObject> pipeline;

	public TraceSegmentReportServiceImpl(TraceSegmentBuffer buffer) {
		this(buffer, new MockOapProperties());
	}

	public TraceSegmentReportServiceImpl(TraceSegmentBuffer buffer, MockOapProperties properties) {
		this.buffer = buffer;
		this.pipeline = MockOapPipelines.start("trace", properties, SegmentObject::getSerializedSize, this::process);
	}

	@Override
	public StreamObserver<SegmentObject> collect(StreamObserver<Commands> responseObserver) {
		return new StreamObserver<SegmentObject>() {

			@Override
			public void onNext(SegmentObject segment) {
				pipeline.submit(segment);
			}

			@Override
			public void onError(Throwable t) {
				log.warn("### [mock-oap] collect stream error: {}", t.getMessage());
			}

			@Override
			public void onCompleted() {
				// 空 Commands = 无命令下发
				responseObserver.onNext(Commands.getDefaultInstance());
				responseObserver.onCompleted();
			}
		};
	}

	private void process(SegmentObject segment) {
		try {
			MockOapTraceRecord record = toRecord(segment);
			warnIfSelfTracing(record);
			buffer.add(record);
			log.info("### [mock-oap] segment received: service={}, traceId={}, spans={}, entry={}, durationMs={}",
					record.getService(), record.getTraceId(), record.getSpanCount(),
					record.getEntryOperation(), record.getDurationMs());
		} catch (Exception e) {
			log.warn("### [mock-oap] failed to convert segment: {}", e.getMessage());
		}
	}

	@Override
	public IngestPipeline<SegmentObject> ingestPipeline() {
		return pipeline;
	}

	/** 从 SegmentObject 抽取列表汇总字段 + span 明细。 */
	private MockOapTraceRecord toRecord(SegmentObject segment) {
		MockOapTraceRecord record = new MockOapTraceRecord();
		record.setTraceId(segment.getTraceId());
		record.setTraceSegmentId(segment.getTraceSegmentId());
		record.setService(segment.getService());
		record.setServiceInstance(segment.getServiceInstance());
		record.setSpanCount(segment.getSpansCount());
		record.setReceivedAt(System.currentTimeMillis());

		long start = Long.MAX_VALUE;
		long end = Long.MIN_VALUE;
		boolean error = false;

		for (SpanObject span : segment.getSpansList()) {
			MockOapSpan s = new MockOapSpan();
			s.setSpanId(span.getSpanId());
			s.setParentSpanId(span.getParentSpanId());
			s.setOperationName(span.getOperationName());
			s.setSpanType(span.getSpanType() == null ? null : span.getSpanType().name());
			s.setSpanLayer(span.getSpanLayer() == null ? null : span.getSpanLayer().name());
			s.setStartTime(span.getStartTime());
			s.setEndTime(span.getEndTime());
			s.setError(span.getIsError());
			s.setComponentId(span.getComponentId());
			s.setPeer(span.getPeer());

			for (KeyStringValuePair tag : span.getTagsList()) {
				s.getTags().put(tag.getKey(), truncate(tag.getValue()));
			}
			for (SegmentReference ref : span.getRefsList()) {
				MockOapSpanRef r = new MockOapSpanRef();
				RefType refType = ref.getRefType();
				r.setRefType(refType == null ? null : refType.name());
				r.setParentTraceSegmentId(ref.getParentTraceSegmentId());
				r.setParentSpanId(ref.getParentSpanId());
				r.setParentService(ref.getParentService());
				r.setParentEndpoint(ref.getParentEndpoint());
				r.setNetworkAddressUsedAtPeer(ref.getNetworkAddressUsedAtPeer());
				s.getRefs().add(r);
			}
			record.getSpans().add(s);

			if (span.getStartTime() > 0 && span.getStartTime() < start) {
				start = span.getStartTime();
			}
			if (span.getEndTime() > end) {
				end = span.getEndTime();
			}
			if (span.getIsError()) {
				error = true;
			}
			if (span.getSpanType() == SpanType.Entry) {
				record.setEntryOperation(span.getOperationName());
			}
		}

		if (start == Long.MAX_VALUE) {
			start = 0;
		}
		if (end == Long.MIN_VALUE) {
			end = 0;
		}
		record.setStartTime(start);
		record.setEndTime(end);
		record.setDurationMs(end > start ? end - start : 0);
		record.setError(error);
		return record;
	}

	private static String truncate(String v) {
		if (v == null) {
			return null;
		}
		return v.length() <= MAX_TAG_VALUE_LEN ? v : v.substring(0, MAX_TAG_VALUE_LEN) + "...";
	}

	/**
	 * 自激检测:若段里出现「本 collector 自己的 gRPC 服务」的 span,说明 SW Java agent 的
	 * grpc-1.x 插件把进程内 mock-OAP 的 gRPC server 也埋点了 —— 每收到一个段就会再生成一个 span,
	 * 再作为新段发回本 server,形成自激循环(实测会把真实链路挤出缓冲)。
	 *
	 * <p>这里只打一条显眼 WARN(30s 节流),不改变数据处理;真正的修法是排除该插件。
	 */
	private void warnIfSelfTracing(MockOapTraceRecord record) {
		String hit = null;
		for (MockOapSpan span : record.getSpans()) {
			String op = span.getOperationName();
			if (op != null && op.startsWith(SELF_TRACE_OP_PREFIX)) {
				hit = op;
				break;
			}
		}
		if (hit == null) {
			return;
		}
		long now = System.currentTimeMillis();
		long last = lastSelfTraceWarnAt.get();
		if (now - last < SELF_TRACE_WARN_INTERVAL_MS || !lastSelfTraceWarnAt.compareAndSet(last, now)) {
			return;
		}
		log.warn("### [mock-oap] 检测到自激(SELF-TRACING):收到来自 SkyWalking 自身上报通道的段, operation={}。"
				+ " 这通常意味着 SW Java agent 的 grpc-1.x 插件把本进程内 mock-OAP 的 gRPC server 也埋点了,"
				+ " 每收到一个段就会再生成一个 span -> 无限循环。"
				+ " 处置:给该应用的 agent 设置 SW_EXCLUDE_PLUGINS=grpc-1.x,"
				+ " 或把 apm-grpc-1.x-plugin 从 plugins 移到 optional-plugins。"
				+ " (同类告警 30s 内只打一次)", hit);
	}
}
