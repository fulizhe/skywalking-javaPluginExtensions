package org.apache.skywalking.apm.agent.core.reporter.logfile;

import static org.apache.skywalking.apm.agent.core.conf.Config.Buffer.BUFFER_SIZE;
import static org.apache.skywalking.apm.agent.core.conf.Config.Buffer.CHANNEL_SIZE;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.context.TracingContext;
import org.apache.skywalking.apm.agent.core.context.TracingContextListener;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient;
import org.apache.skywalking.apm.commons.datacarrier.DataCarrier;
import org.apache.skywalking.apm.commons.datacarrier.buffer.BufferStrategy;
import org.apache.skywalking.apm.commons.datacarrier.consumer.IConsumer;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;

/**
 * <p>
 * A tracing segment data reporter.
 * <p>
 * {@code DyanmicEnabledTraceSegmentServiceClient}
 */
@OverrideImplementor(TraceSegmentServiceClient.class)
public class LogFileTraceSegmentServiceClient extends TraceSegmentServiceClient
		implements BootService, IConsumer<TraceSegment>, TracingContextListener {

	private static final ILog LOGGER = LogManager.getLogger(LogFileTraceSegmentServiceClient.class);

	// private Producer<byte[]> producer;

	private volatile DataCarrier<TraceSegment> carrier;

	private final AtomicBoolean enable;

	private int maxLogSize = 1000;
	// 借鉴自Druid的JdbcDataSourceStat
	private final LinkedHashMap<String, Map<String, Object>> logfileStatMap;

	public LogFileTraceSegmentServiceClient() {
		this.enable = new AtomicBoolean(true);

		logfileStatMap = new LinkedHashMap<String, Map<String, Object>>(16, 0.75f, false) {
			private static final long serialVersionUID = 1L;

			protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
				boolean remove = (size() > maxLogSize);
//				if (remove) {
//					Object sqlStat = eldest.getValue();
//					// if (sqlStat.getRunningCount() > 0 || sqlStat.getExecuteCount() > 0) {
//					// skipSqlCount.incrementAndGet();
//					// }
//				}
				return remove;
			}
		};
	}

	// ==================================== @

	public void toggleEnable(boolean e) {
		if (LOGGER.isDebugEnable()) {
			LOGGER.debug("### Updating using new static config: {}", e);
		}

		enable.getAndSet(e);
		LOGGER.warn("### [ {} ] current logfile-reporter-enable status is [ {} ]", Config.Agent.SERVICE_NAME,
				isEnbaleLogfileReporter());
	}

	public boolean isEnbaleLogfileReporter() {
		return enable.get();
	}

	public Map<String, Map<String, Object>> getLogfileStatMap() {
		return logfileStatMap;
	}

	// ==================================== @Override

	@Override
	public void prepare() {
//        PulsarProducerManager producerManager = ServiceManager.INSTANCE.findService(PulsarProducerManager.class);
//        producerManager.addListener(this);
		maxLogSize = LogFileReporterPluginConfig.Plugin.LogFileReporter.MAX_LOG_SIZE != null
				? LogFileReporterPluginConfig.Plugin.LogFileReporter.MAX_LOG_SIZE
				: maxLogSize;

		LOGGER.warn("### prepare - LogFileTraceSegmentServiceClient - maxLogSize is [ {} ]", maxLogSize);

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
		// 触发时机：由 DataCarrier（数据传输队列）批量消费时触发。DataCarrier 会把队列里的 TraceSegment 批量取出，调用
		// consume(List<TraceSegment> data)。
		// 你可以在这里做“批量 TraceSegment 的统一处理”，比如：批量序列化、写日志、落盘、上报等。

		if (!isEnbaleLogfileReporter()) {
			LOGGER.info(
					"###consume. disable the logfile-reporter [ {} ] which save data to log-file. the collection size of data is [ {} ]",
					Config.Agent.SERVICE_NAME, data.size());
			return;
		}

		if (LOGGER.isDebugEnable()) {
			LOGGER.info(
					"### current logfile-reporter status [ {} ] is [ {} ], the colletion size of data is [ {} ], the colletion size of cache is [ {} ]",
					Config.Agent.SERVICE_NAME, isEnbaleLogfileReporter(), data.size(), logfileStatMap.size());
		}
		// 《SW原理 - 基本概念 （ TraceSegment ）》
		// 1. 一个trace由多个tracesegment构成
		// 2. 一个Tracesegemnt记录了一个请求在一个线程中的执行流程
		// 3. 一个TraceSegment内包含一个Span集合
		// 4. TraceSegment 的核心字段结构如下：
		// 4.1 traceSegmentId（ID 类型）：通过GlobalIdGenerator 生成，是TraceSegment 的全局唯一标识。
		// 4.2 ref（TraceSegmentRef类型）：它指向父 TraceSegment。在 RPC 调用、HTTP 请求等跨进程调用中，一个
		// TraceSegment 最多只有一个父 TraceSegment，但是在一个 Consumer 批量消费 MQ 消息时，同一批内的消息可能来自不同的
		// Producer，这就会导致 Consumer 线程对应的 TraceSegment 有多个父 TraceSegment了，系统只保留第一个父
		// TraceSegment,早期版本是保留了全部 。
		// 4.3 relatedGlobalTraceId（DistributedTraceIds 类型）：记录当前 TraceSegment 所属 Trace 的
		// Trace ID，批处理场景下也只保留第一个。
		// 4.4 spans（List<AbstractTracingSpan> 类型）：当前 TraceSegment 包含的所有 Span。
		// 4.5 ignore（boolean 类型）：ignore 字段表示当前 TraceSegment是否被忽略。主要是为了忽略一些问题
		// TraceSegment（据说是对只包含一个 Span 的 Trace 进行采样收集）。
		// 4.6 isSizeLimited（boolean 类型）：每个 TraceSegment 中 Span 的个数是有上限的（默认值为
		// 300，可动态配置），超过上限之后，就不再添加 Span了；这是一个内存保护措施。
		// 将 SegmentObject 转换为自定义的 Log 对象，并存入缓存，供外部读取
		final List<SegmentObject> collect = data.stream().map(TraceSegment::transform).collect(Collectors.toList());
		// final String globalTraceid = collect.get(0).getTraceId();
		// final List<Log> logList = new ArrayList<>();
		for (SegmentObject segment : collect) {
			Log log = new Log();
			// 假设 Log 类有对应的 setter 方法，或者构造方法
			// 1. traceId：全局唯一，标识一次完整的分布式调用。
			// 2. traceSegmentId：局部唯一，标识某个服务/线程/进程中的一个调用片段。
			// 3. 一个 traceId 下可以有多个 traceSegmentId，它们通过“引用关系”串联成完整的调用链。
			log.setTraceId(segment.getTraceId());
			log.setTraceSegmentId(segment.getTraceSegmentId());
			log.setService(segment.getService());
			log.setServiceInstance(segment.getServiceInstance());
			log.setIsSizeLimited(segment.getIsSizeLimited());

			List<Log.SpanInfo> spanInfoList = new ArrayList<>();
			for (SpanObject span : segment.getSpansList()) {
				Log.SpanInfo spanInfo = new Log.SpanInfo();
				spanInfo.setSpanId(span.getSpanId());
				spanInfo.setOperationName(span.getOperationName());
				spanInfo.setStartTime(span.getStartTime());
				spanInfo.setEndTime(span.getEndTime());
				spanInfo.setSpanType(span.getSpanType().toString());
				spanInfo.setSpanLayer(span.getSpanLayer().toString());
				spanInfo.setComponentId(span.getComponentId());
				spanInfo.setIsError(span.getIsError());
				// 增加logCount字段和tag集合的处理，便于后续序列化和展示
				// 假设SpanObject有getLogCount()和getTagsList()方法
				spanInfo.setLogCount(span.getLogsCount());
				// 处理tag集合，假设tag为键值对结构
				List<Map<String, Object>> tagList = new ArrayList<>();
				if (span.getTagsList() != null) {
					for (KeyStringValuePair tag : span.getTagsList()) {
						Map<String, Object> tagMap = new HashMap<>();
						tagMap.put("tag-key", tag.getKey());
						tagMap.put("tag-value", tag.getValue());
						tagList.add(tagMap);
					}
				}
				spanInfo.setTagList(tagList);
				spanInfoList.add(spanInfo);
			}
			log.setSpans(spanInfoList);
			// logList.add(log);

			final String globalTraceid = log.getTraceId();
			// 这里是traceId一样的放到一起
			// 先判断当前traceId是否已存在于logfileStatMap中，如果存在则合并logList，否则直接放入
			if (logfileStatMap.containsKey(globalTraceid)) {
				// 已有该traceId，合并log
				Object obj = logfileStatMap.get(globalTraceid);
				if (obj instanceof Map) {
					Map<String, Object> map = (Map<String, Object>) obj;
					Object logsObj = map.get("logs");
					if (logsObj instanceof List) {
						List<Map<String, Object>> logs = (List<Map<String, Object>>) logsObj;
						// 将当前log对象转为map并加入
						logs.add(log.toMap());
					}
				}
			} else {
				// 没有该traceId，直接put一个新的LogCollection
				logfileStatMap.put(globalTraceid, new LogCollection(new ArrayList<Log>() {
					{
						add(log);
					}
				}).toMap());
			}
		}
		// logfileStatMap.put(globalTraceid, new LogCollection(logList).toMap());
	}

	@Override
	public void onError(final List<TraceSegment> data, final Throwable t) {
		LOGGER.error(t, "Try to send {} trace segments to collector, with unexpected exception.", data.size());
	}

	@Override
	public void onExit() {
		carrier.shutdownConsumers();
	}

	@Override
	public void afterFinished(final TraceSegment traceSegment) {
		// afterFinished(final TraceSegment traceSegment) 方法会在每个 TraceSegment
		// 完成（即一次完整的链路追踪数据采集结束）时被 SkyWalking Agent 回调。
		// 原理是：SkyWalking 的核心链路追踪逻辑（如 TracingContext）在采集完一段 Trace 后，会遍历注册的
		// TracingContextListener，依次调用其 afterFinished 方法，把刚刚完成的 TraceSegment
		// 传递给监听者，实现自定义处理（如上报、落盘等）。
		// 你可以在这里做“单条 TraceSegment 完成后的自定义处理”，比如：把它放到队列、缓存、异步处理等。
		// 这样可以解耦采集与后续处理逻辑。
		if (LOGGER.isDebugEnable()) {
			LOGGER.debug("Trace segment reporting, traceId: {}", traceSegment.getTraceSegmentId());
		}

		if (traceSegment.isIgnore()) {
			LOGGER.debug("Trace[TraceId={}] is ignored.", traceSegment.getTraceSegmentId());
			return;
		}

		if (!isEnbaleLogfileReporter()) {
			LOGGER.info("### afterFinished. disable the logfile-reporter [ {} ] which save data to log-file.",
					Config.Agent.SERVICE_NAME);
			return;
		}
		carrier.produce(traceSegment);
//
//		// =====================================================================
//		final SegmentObject segment = traceSegment.transform();
//		Log log = new Log();
//		// 假设 Log 类有对应的 setter 方法，或者构造方法
//		log.setTraceId(segment.getTraceId());
//		log.setTraceSegmentId(segment.getTraceSegmentId());
//		log.setService(segment.getService());
//		log.setServiceInstance(segment.getServiceInstance());
//		log.setIsSizeLimited(segment.getIsSizeLimited());
//
//		List<Log.SpanInfo> spanInfoList = new ArrayList<>();
//		for (SpanObject span : segment.getSpansList()) {
//			Log.SpanInfo spanInfo = new Log.SpanInfo();
//			spanInfo.setSpanId(span.getSpanId());
//			spanInfo.setOperationName(span.getOperationName());
//			spanInfo.setStartTime(span.getStartTime());
//			spanInfo.setEndTime(span.getEndTime());
//			spanInfo.setSpanType(span.getSpanType().toString());
//			spanInfo.setSpanLayer(span.getSpanLayer().toString());
//			spanInfo.setComponentId(span.getComponentId());
//			spanInfo.setIsError(span.getIsError());
//			spanInfoList.add(spanInfo);
//		}
//		log.setSpans(spanInfoList);
//
//		final String globalTraceid = segment.getTraceId();
//		logfileStatMap.put(globalTraceid, log.toMap());
	}

	@Override
	public void init(Properties arg0) {
		// TODO Auto-generated method stub

	}
}