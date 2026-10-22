package com.fulizhe.observability.mockoap.log;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.skywalking.apm.dependencies.io.grpc.stub.StreamObserver;
import org.apache.skywalking.apm.network.common.v3.Commands;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.apm.network.logging.v3.LogDataBody;
import org.apache.skywalking.apm.network.logging.v3.LogReportServiceGrpc;
import org.apache.skywalking.apm.network.logging.v3.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fulizhe.observability.mockoap.MockOapPipelines;
import com.fulizhe.observability.mockoap.MockOapProperties;
import com.fulizhe.observability.mockoap.ingest.IngestLifecycle;
import com.fulizhe.observability.mockoap.ingest.IngestPipeline;

/**
 * SkyWalking 原生协议 {@code LogReportService.collect}(客户端流)的服务端实现。
 *
 * <p>形状与 {@link com.fulizhe.observability.mockoap.trace.TraceSegmentReportServiceImpl} 一致:收 {@link LogData} 流,逐条抽取后写入
 * {@link LogBuffer},最后以空 {@link Commands} 应答(表示无命令下发)。这样 logback 里的
 * {@code GRPCLogClientAppender} 不再拿到 {@code UNIMPLEMENTED},日志也被本地收进来了。
 *
 * <p><b>防递归</b>:采集器自己的日志再被 appender 发回本进程就会无限放大,所以
 * (1) 每条接收日志只打 {@code DEBUG}(root 级别是 INFO,不会真的写出去);
 * (2) 入库前由 {@link LogBuffer} 用 {@link MockOapLogs#isSelfLog} 拒收自身日志。
 */
public class LogReportServiceImpl extends LogReportServiceGrpc.LogReportServiceImplBase implements IngestLifecycle {

	private static final Logger log = LoggerFactory.getLogger(LogReportServiceImpl.class);

	/** 单条正文截断上限(防大字段撑爆内存)。 */
	private static final int MAX_CONTENT_LEN = 2000;

	/** 单个 tag 值截断上限。 */
	private static final int MAX_TAG_VALUE_LEN = 256;

	/** 拒收自己时的告警节流(30s 一次),避免循环时把日志刷爆。 */
	private static final long SELF_LOG_WARN_INTERVAL_MS = 30_000L;

	private final LogBuffer buffer;
	private final IngestPipeline<LogData> pipeline;

	private final AtomicLong lastSelfLogWarnAt = new AtomicLong(0L);

	public LogReportServiceImpl(LogBuffer buffer) {
		this(buffer, new MockOapProperties());
	}

	public LogReportServiceImpl(LogBuffer buffer, MockOapProperties properties) {
		this.buffer = buffer;
		this.pipeline = MockOapPipelines.start("log", properties, LogData::getSerializedSize, this::process);
	}

	@Override
	public StreamObserver<LogData> collect(StreamObserver<Commands> responseObserver) {
		return new StreamObserver<LogData>() {

			@Override
			public void onNext(LogData data) {
				pipeline.submit(data);
			}

			@Override
			public void onError(Throwable t) {
				log.warn("### [mock-oap] log stream error: {}", t.getMessage());
			}

			@Override
			public void onCompleted() {
				// 空 Commands = 无命令下发
				responseObserver.onNext(Commands.getDefaultInstance());
				responseObserver.onCompleted();
			}
		};
	}

	private void process(LogData data) {
		try {
			MockOapLogRecord record = toRecord(data);
			if (buffer.add(record)) {
				// 只到 DEBUG:root 级别 INFO,不会真的落到 appender,切断放大回路
				log.debug("### [mock-oap] log received: service={}, level={}, traceId={}",
						record.getService(), record.getLevel(), record.getTraceId());
			} else {
				warnIfDroppedSelf(record);
			}
		} catch (Exception e) {
			log.warn("### [mock-oap] failed to convert log: {}", e.getMessage());
		}
	}

	@Override
	public IngestPipeline<LogData> ingestPipeline() {
		return pipeline;
	}

	/** 把协议报文抽成列表/详情页要用的记录(包级可见,便于单测)。 */
	MockOapLogRecord toRecord(LogData data) {
		MockOapLogRecord r = new MockOapLogRecord();
		r.setTimestamp(data.getTimestamp());
		r.setService(data.getService());
		r.setServiceInstance(data.getServiceInstance());
		r.setEndpoint(data.getEndpoint());
		r.setLayer(data.getLayer());
		r.setReceivedAt(System.currentTimeMillis());

		LogDataBody body = data.getBody();
		LogDataBody.ContentCase contentCase = body.getContentCase();
		String content = "";
		if (contentCase == LogDataBody.ContentCase.TEXT) {
			content = body.getText().getText();
		} else if (contentCase == LogDataBody.ContentCase.JSON) {
			content = body.getJson().getJson();
		} else if (contentCase == LogDataBody.ContentCase.YAML) {
			content = body.getYaml().getYaml();
		}
		r.setContentType(contentCase == LogDataBody.ContentCase.CONTENT_NOT_SET ? "NONE" : contentCase.name());
		r.setContent(MockOapLogs.truncate(content == null ? "" : content, MAX_CONTENT_LEN));

		if (data.hasTraceContext()) {
			TraceContext ctx = data.getTraceContext();
			// Go agent 没有链路时用 "N/A" 作哨兵值;统一归一成 null,页面显示 "-" 而不是假的 traceId
			r.setTraceId(normalizeTraceId(ctx.getTraceId()));
			r.setTraceSegmentId(normalizeTraceId(ctx.getTraceSegmentId()));
			r.setSpanId(ctx.getSpanId());
		}

		if (data.hasTags()) {
			for (KeyStringValuePair kv : data.getTags().getDataList()) {
				r.getTags().put(kv.getKey(), MockOapLogs.truncate(kv.getValue(), MAX_TAG_VALUE_LEN));
			}
		}

		r.setLevel(MockOapLogs.detectLevel(r.getContent(), r.getTags()));
		return r;
	}

	/** agent 的「无链路」哨兵值("N/A")归一为 null;真实 id 原样保留。 */
	private static String normalizeTraceId(String id) {
		if (id == null || id.trim().isEmpty() || "N/A".equalsIgnoreCase(id.trim())) {
			return null;
		}
		return id;
	}

	/** 拒收自己时的显眼告警(节流),说明为什么日志没进缓冲。 */
	private void warnIfDroppedSelf(MockOapLogRecord record) {
		long now = System.currentTimeMillis();
		long last = lastSelfLogWarnAt.get();
		if (now - last < SELF_LOG_WARN_INTERVAL_MS || !lastSelfLogWarnAt.compareAndSet(last, now)) {
			return;
		}
		log.warn("### [mock-oap] 拒收一条采集器自身产生的日志(防止「收日志 -> 打日志 -> 再被采集」的放大回路)。"
				+ " content={}", MockOapLogs.truncate(record.getContent(), 120));
	}
}
