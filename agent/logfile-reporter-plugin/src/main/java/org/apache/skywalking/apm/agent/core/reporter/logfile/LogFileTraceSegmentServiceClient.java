package org.apache.skywalking.apm.agent.core.reporter.logfile;

import static org.apache.skywalking.apm.agent.core.conf.Config.Buffer.BUFFER_SIZE;
import static org.apache.skywalking.apm.agent.core.conf.Config.Buffer.CHANNEL_SIZE;

import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.context.TracingContext;
import org.apache.skywalking.apm.agent.core.context.TracingContextListener;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient;
import org.apache.skywalking.apm.commons.datacarrier.DataCarrier;
import org.apache.skywalking.apm.commons.datacarrier.buffer.BufferStrategy;
import org.apache.skywalking.apm.commons.datacarrier.consumer.IConsumer;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;

import cn.hutool.json.JSONUtil;

/**
 * A tracing segment data reporter.
 */
@OverrideImplementor(TraceSegmentServiceClient.class)
public class LogFileTraceSegmentServiceClient implements BootService, IConsumer<TraceSegment>, TracingContextListener {

	private static final ILog LOGGER = LogManager.getLogger(LogFileTraceSegmentServiceClient.class);

	private String topic;
	// private Producer<byte[]> producer;

	private volatile DataCarrier<TraceSegment> carrier;

	@Override
	public void prepare() {
//        PulsarProducerManager producerManager = ServiceManager.INSTANCE.findService(PulsarProducerManager.class);
//        producerManager.addListener(this);
		topic = LogFileReporterPluginConfig.Plugin.LogFile.TOPIC_SEGMENT;

		LOGGER.warn("### prepare - LogFileTraceSegmentServiceClient - {}", topic);
	}

	@Override
	public void boot() {
		carrier = new DataCarrier<>(CHANNEL_SIZE, BUFFER_SIZE, BufferStrategy.IF_POSSIBLE);
		carrier.consume(this, 1);
	}

	@Override
	public void onComplete() {
		TracingContext.ListenerManager.add(this);
	}

	@Override
	public void shutdown() {
		TracingContext.ListenerManager.remove(this);
		carrier.shutdownConsumers();
	}

	@Override
	public void consume(final List<TraceSegment> data) {		
		
//    	data.forEach(traceSegment -> {
//            SegmentObject upstreamSegment = traceSegment.transform();
//            
//            LOGGER.info("### SegmentObject: {}", JSONUtil.toJsonStr(upstreamSegment));
//        }); 
		
		// 《SW原理 - 基本概念 （ TraceSegment ）》
		// 1. 一个trace由多个tracesegment构成
		// 2. 一个Tracesegemnt记录了一个请求在一个线程中的执行流程
		// 3. 一个TraceSegment内包含一个Span集合
		// 4. TraceSegment 的核心字段结构如下：
		//	4.1 traceSegmentId（ID 类型）：通过GlobalIdGenerator 生成，是TraceSegment 的全局唯一标识。
		//  4.2 ref（TraceSegmentRef类型）：它指向父 TraceSegment。在 RPC 调用、HTTP 请求等跨进程调用中，一个 TraceSegment 最多只有一个父 TraceSegment，但是在一个 Consumer 批量消费 MQ 消息时，同一批内的消息可能来自不同的 Producer，这就会导致 Consumer 线程对应的 TraceSegment 有多个父 TraceSegment了，系统只保留第一个父 TraceSegment,早期版本是保留了全部 。
		//  4.3 relatedGlobalTraceId（DistributedTraceIds 类型）：记录当前 TraceSegment 所属 Trace 的 Trace ID，批处理场景下也只保留第一个。
		//  4.4 spans（List<AbstractTracingSpan> 类型）：当前 TraceSegment 包含的所有 Span。
		//  4.5 ignore（boolean 类型）：ignore 字段表示当前 TraceSegment是否被忽略。主要是为了忽略一些问题 TraceSegment（据说是对只包含一个 Span 的 Trace 进行采样收集）。
		//  4.6 isSizeLimited（boolean 类型）：每个 TraceSegment 中 Span 的个数是有上限的（默认值为 300，可动态配置），超过上限之后，就不再添加 Span了；这是一个内存保护措施。		
		final List<SegmentObject> collect = data.stream().map(TraceSegment::transform).collect(Collectors.toList());
		for (SegmentObject segment : collect) {
			LOGGER.info("Trace Segment Info:");
			LOGGER.info("  Trace ID: {}", segment.getTraceId());
			LOGGER.info("  Segment ID: {}", segment.getTraceSegmentId());
			LOGGER.info("  Service: {}", segment.getService());
			LOGGER.info("  Service Instance: {}", segment.getServiceInstance());
			LOGGER.info("  Is Size Limited: {}", segment.getIsSizeLimited());
			
			LOGGER.info("  Spans:");
			for (SpanObject span : segment.getSpansList()) {
				LOGGER.info("    - Span ID: {}", span.getSpanId());
				LOGGER.info("      Operation Name: {}", span.getOperationName());
				LOGGER.info("      Start Time: {}", span.getStartTime());
				LOGGER.info("      End Time: {}", span.getEndTime());
				LOGGER.info("      Span Type: {}", span.getSpanType());
				LOGGER.info("      Span Layer: {}", span.getSpanLayer());
				LOGGER.info("      Component ID: {}", span.getComponentId());
				LOGGER.info("      Is Error: {}", span.getIsError());
			}
		}		
		LOGGER.info("### consume-SegmentObjectList【{}】: {}", data.get(0).getTraceSegmentId(), JSONUtil.toJsonStr(collect));
	}

	@Override
	public void onError(final List<TraceSegment> data, final Throwable t) {
		LOGGER.error(t, "Try to send {} trace segments to collector, with unexpected exception.", data.size());
	}

	@Override
	public void onExit() {

	}

	@Override
	public void afterFinished(final TraceSegment traceSegment) {
		if (LOGGER.isDebugEnable()) {
			LOGGER.debug("Trace segment reporting, traceId: {}", traceSegment.getTraceSegmentId());
		}

		if (traceSegment.isIgnore()) {
			LOGGER.debug("Trace[TraceId={}] is ignored.", traceSegment.getTraceSegmentId());
			return;
		}
		carrier.produce(traceSegment);

		LOGGER.info("### afterFinished-SegmentObject: {}", traceSegment.toString());
	}

	@Override
	public void init(Properties arg0) {
		// TODO Auto-generated method stub

	}
}