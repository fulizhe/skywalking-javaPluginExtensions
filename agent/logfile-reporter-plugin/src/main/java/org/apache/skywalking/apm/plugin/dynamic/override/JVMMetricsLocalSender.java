package org.apache.skywalking.apm.plugin.dynamic.override;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelStatus;
import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;
import org.apache.skywalking.apm.agent.core.jvm.JVMMetricsSender;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.network.language.agent.v3.Class;
import org.apache.skywalking.apm.network.language.agent.v3.GC;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetric;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetricCollection;
import org.apache.skywalking.apm.network.language.agent.v3.Memory;
import org.apache.skywalking.apm.network.language.agent.v3.MemoryPool;
import org.apache.skywalking.apm.network.language.agent.v3.Thread;
import org.apache.skywalking.apm.toolkit.CircularBlockingQueue;

/**
 * <p>
 * JVMMetricsSender 的本地实现：
 * 收集到的 JVM Metrics 数据不再通过网络发送给 OAP 端，
 * 而是转换成结构化数据后缓存在本地内存（基于有界队列，仅保留最近 N 条），
 * 供日志上报 / 前端展示等组件按需读取。
 * </p>
 * <p>
 * Refer to {@code DisableJVMService}
 * </p>
 *
 *<a href=" https://github.com/apache/skywalking-java/blob/main/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/jvm/JVMMetricsSender.java">...</a>a
 */
@OverrideImplementor(JVMMetricsSender.class)
public class JVMMetricsLocalSender extends JVMMetricsSender implements BootService, Runnable {
	private static final ILog LOGGER = LogManager.getLogger(JVMMetricsLocalSender.class);

	// jvmMetricsDataCache不能替代它：一个是采集暂存，一个是转换后的历史缓存。
	private LinkedBlockingQueue<JVMMetric> queue;

	/** 本地 JVM 指标缓存条数上限，由 {@link LogFileReporterPluginConfig.Plugin#JvmMetricsLocal} 配置，默认 1000 */
	private int maxMetricsDataSize;
	/**
	 * JVM 指标本地有界缓存，仅保留最近 N 条。
	 * 使用 Deque 避免 LinkedHashMap （参考自: Druid中的 JdbcDataSourceStat）仅为 removeEldestEntry 的额外语义和开销。
	 */
	// TODO 参考 KafkaJVMMetricsSender.java 使用 BlockingQueue 来处理
	// https://github.com/apache/skywalking-java/blob/e0e8b3c8c304735991e057d431910ed1f4a57cdd/apm-sniffer/optional-reporter-plugins/kafka-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/kafka/KafkaJVMMetricsSender.java
	private CircularBlockingQueue<java.util.Map<String, Object>> jvmMetricsDataCache;

	@Override
	public void prepare() {
		queue = new LinkedBlockingQueue<>(Config.Jvm.BUFFER_SIZE);
		// ServiceManager.INSTANCE.findService(GRPCChannelManager.class).addChannelListener(this);

		Integer configured = LogFileReporterPluginConfig.Plugin.JvmMetricsLocal.MAX_METRICS_DATA_SIZE;
		this.maxMetricsDataSize = (configured != null && configured > 0) ? configured : 1000;

		jvmMetricsDataCache = new CircularBlockingQueue<>(this.maxMetricsDataSize);
	}

	@Override
	public void boot() {
		//NONE
	}

	public Collection<java.util.Map<String, Object>> getMetrics() {
		// 返回快照，避免调用方在遍历时与写线程并发修改导致异常
		return new java.util.ArrayList<>(jvmMetricsDataCache);
	}
	
	@Override
	public void offer(JVMMetric metric) {		
		// drop last message and re-deliver
		if (!queue.offer(metric)) {
			queue.poll();
			queue.offer(metric);
		}
	}

	@Override
	public void run() {
		try {
			LinkedList<JVMMetric> buffer = drainQueueToBuffer();
			if (!buffer.isEmpty()) {
				JVMMetricCollection jvmMetricCollection = buildJVMMetricCollection(buffer);
				Map<String, Object> metricMap = buildMetricCollectionMap(jvmMetricCollection);				
				// 将最新指标写入本地有界缓存，超出上限时淘汰最老数据。				 	
				jvmMetricsDataCache.add(metricMap);
			}
		} catch (Throwable t) {
			LOGGER.error(t, "collect JVM metrics to local cache fail.");
			// ServiceManager.INSTANCE.findService(GRPCChannelManager.class).reportError(t);
		}

	}

	/**
	 * 从队列中一次性拉取当前所有 JVMMetric，避免在 run 周期内反复竞争队列锁。
	 */
	private LinkedList<JVMMetric> drainQueueToBuffer() {
		LinkedList<JVMMetric> buffer = new LinkedList<>();
		queue.drainTo(buffer);
		return buffer;
	}

	/**
	 * 将 JVMMetric 列表封装为 JVMMetricCollection，补全 service / instance 信息。
	 */
	private JVMMetricCollection buildJVMMetricCollection(java.util.List<JVMMetric> metrics) {
		JVMMetricCollection.Builder builder = JVMMetricCollection.newBuilder();
		builder.addAllMetrics(metrics);
		builder.setService(Config.Agent.SERVICE_NAME);
		builder.setServiceInstance(Config.Agent.INSTANCE_NAME);
		return builder.build();
	}

	/**
	 * 将 JVMMetricCollection 转换为结构化 Map，便于本地缓存与下游消费。
	 */
	private java.util.Map<String, Object> buildMetricCollectionMap(JVMMetricCollection jvmMetricCollection) {
		final java.util.Map<String, Object> metricMap = new java.util.HashMap<>();
		metricMap.put("service", jvmMetricCollection.getService());
		metricMap.put("serviceInstance", jvmMetricCollection.getServiceInstance());

		final java.util.List<java.util.Map<String, Object>> metricsList = new java.util.ArrayList<>();
		for (JVMMetric metric : jvmMetricCollection.getMetricsList()) {
			metricsList.add(buildSingleMetricMap(metric));
		}
		metricMap.put("metrics", metricsList);
		return metricMap;
	}

	/**
	 * 单条 JVMMetric 的结构化转换逻辑，方便单元测试覆盖。
	 */
	private java.util.Map<String, Object> buildSingleMetricMap(JVMMetric metric) {
		java.util.Map<String, Object> m = new java.util.HashMap<>();
		m.put("time", metric.getTime());
		m.put("cpu", metric.getCpu().getUsagePercent());

		m.put("memory", buildMemoryList(metric));
		m.put("memoryPool", buildMemoryPoolList(metric));
		m.put("gc", buildGcList(metric));
		m.put("thread", buildThreadMap(metric.getThread()));
		m.put("clazz", buildClassMap(metric.getClazz()));

		return m;
	}

	private java.util.List<java.util.Map<String, Object>> buildMemoryList(JVMMetric metric) {
		java.util.List<java.util.Map<String, Object>> memoryList = new java.util.ArrayList<>();
		for (Memory memory : metric.getMemoryList()) {
			java.util.Map<String, Object> memMap = new java.util.HashMap<>();
			memMap.put("isHeap", memory.getIsHeap());
			memMap.put("init", memory.getInit());
			memMap.put("max", memory.getMax());
			memMap.put("used", memory.getUsed());
			memMap.put("committed", memory.getCommitted());
			memoryList.add(memMap);
		}
		return memoryList;
	}

	private java.util.List<java.util.Map<String, Object>> buildMemoryPoolList(JVMMetric metric) {
		java.util.List<java.util.Map<String, Object>> memoryPoolList = new java.util.ArrayList<>();
		for (MemoryPool pool : metric.getMemoryPoolList()) {
			java.util.Map<String, Object> poolMap = new java.util.HashMap<>();
			poolMap.put("type", pool.getType());
			poolMap.put("init", pool.getInit());
			poolMap.put("max", pool.getMax());
			poolMap.put("used", pool.getUsed());
			poolMap.put("committed", pool.getCommitted());
			memoryPoolList.add(poolMap);
		}
		return memoryPoolList;
	}

	private java.util.List<java.util.Map<String, Object>> buildGcList(JVMMetric metric) {
		java.util.List<java.util.Map<String, Object>> gcList = new java.util.ArrayList<>();
		for (GC gc : metric.getGcList()) {
			java.util.Map<String, Object> gcMap = new java.util.HashMap<>();
			gcMap.put("phrase", gc.getPhase().name());
			gcMap.put("count", gc.getCount());
			gcMap.put("time", gc.getTime());
			gcList.add(gcMap);
		}
		return gcList;
	}

	private java.util.Map<String, Object> buildThreadMap(Thread thread) {
		java.util.Map<String, Object> threadMap = new java.util.HashMap<>();
		threadMap.put("daemon", thread.getDaemonCount()); // 守护线程数
		threadMap.put("live", thread.getLiveCount());     // 活跃线程数
		threadMap.put("peak", thread.getPeakCount());     // 峰值线程数
		threadMap.put("blocked", thread.getBlockedStateThreadCount());           // blocked状态线程数
		threadMap.put("runnable", thread.getRunnableStateThreadCount());         // runnable状态线程数
		threadMap.put("timed_waiting", thread.getTimedWaitingStateThreadCount()); // timed_waiting状态线程数
		threadMap.put("waiting", thread.getWaitingStateThreadCount());           // waiting状态线程数
		return threadMap;
	}

	private java.util.Map<String, Object> buildClassMap(Class clazz) {
		java.util.Map<String, Object> clazzMap = new java.util.HashMap<>();
		clazzMap.put("loaded", clazz.getLoadedClassCount());           // 当前已加载类数量
		clazzMap.put("total_loaded", clazz.getTotalLoadedClassCount()); // 累计加载类数量
		clazzMap.put("total_unloaded", clazz.getTotalUnloadedClassCount()); // 累计卸载类数量
		return clazzMap;
	}

	@Override
	public void onComplete() {

	}

	@Override
	public void shutdown() {

	}

	@Override
	public void statusChanged(GRPCChannelStatus status) {
		LOGGER.warn("### GRPC Disabled. Current GRPCChannelStatus is [ {} ]", status);
	}
}