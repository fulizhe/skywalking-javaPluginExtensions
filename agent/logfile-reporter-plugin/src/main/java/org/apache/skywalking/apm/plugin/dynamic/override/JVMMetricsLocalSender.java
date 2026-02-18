package org.apache.skywalking.apm.plugin.dynamic.override;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.conf.Config;
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

/**
 * <p>
 * JVMMetricsSender 的本地实现：
 * 收集到的 JVM Metrics 数据不再通过网络发送给 OAP 端，
 * 而是转换成结构化数据后缓存在本地内存（基于 {@link LinkedHashMap} 的有界 LRU 缓存），
 * 供日志上报 / 前端展示等组件按需读取。
 * </p>
 * <p>
 * Refer to {@code DisableJVMService}
 * </p>
 */
@OverrideImplementor(JVMMetricsSender.class)
public class JVMMetricsLocalSender extends JVMMetricsSender implements BootService, Runnable {
	private static final ILog LOGGER = LogManager.getLogger(JVMMetricsLocalSender.class);

	private LinkedBlockingQueue<JVMMetric> queue;

	/** 本地 JVM 指标缓存条数上限，由 {@link LogFileReporterPluginConfig.Plugin#JvmMetricsLocal} 配置，默认 1000 */
	private int maxMetricsDataSize;
	// 借鉴自Druid的JdbcDataSourceStat，使用同步包装保证并发访问安全
	private Map<String, Map<String, Object>> jvmMetricsDataCache;

	@Override
	public void prepare() {
		queue = new LinkedBlockingQueue<>(Config.Jvm.BUFFER_SIZE);
		// ServiceManager.INSTANCE.findService(GRPCChannelManager.class).addChannelListener(this);

		Integer configured = LogFileReporterPluginConfig.Plugin.JvmMetricsLocal.MAX_METRICS_DATA_SIZE;
		this.maxMetricsDataSize = (configured != null && configured > 0) ? configured : 1000;

		final int maxSize = this.maxMetricsDataSize;
		jvmMetricsDataCache = Collections.synchronizedMap(
				new LinkedHashMap<String, Map<String, Object>>(16, 0.75f, false) {
					private static final long serialVersionUID = 1L;

					@Override
					protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
						return size() > maxSize;
					}
				});
	}

	@Override
	public void boot() {

	}

	public Collection<Map<String, Object>> getMetrics() {
		// 返回快照，避免调用方在遍历时与写线程并发修改导致异常
		synchronized (jvmMetricsDataCache) {
			return new java.util.ArrayList<>(jvmMetricsDataCache.values());
		}
	}

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
				cacheMetrics(jvmMetricCollection, metricMap);
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
	private Map<String, Object> buildMetricCollectionMap(JVMMetricCollection jvmMetricCollection) {
		final Map<String, Object> metricMap = new java.util.HashMap<>();
		metricMap.put("service", jvmMetricCollection.getService());
		metricMap.put("serviceInstance", jvmMetricCollection.getServiceInstance());

		final java.util.List<Map<String, Object>> metricsList = new java.util.ArrayList<>();
		for (JVMMetric metric : jvmMetricCollection.getMetricsList()) {
			metricsList.add(buildSingleMetricMap(metric));
		}
		metricMap.put("metrics", metricsList);
		return metricMap;
	}

	/**
	 * 单条 JVMMetric 的结构化转换逻辑，方便单元测试覆盖。
	 */
	private Map<String, Object> buildSingleMetricMap(JVMMetric metric) {
		Map<String, Object> m = new java.util.HashMap<>();
		m.put("time", metric.getTime());
		m.put("cpu", metric.getCpu().getUsagePercent());

		m.put("memory", buildMemoryList(metric));
		m.put("memoryPool", buildMemoryPoolList(metric));
		m.put("gc", buildGcList(metric));
		m.put("thread", buildThreadMap(metric.getThread()));
		m.put("clazz", buildClassMap(metric.getClazz()));

		return m;
	}

	private java.util.List<Map<String, Object>> buildMemoryList(JVMMetric metric) {
		java.util.List<Map<String, Object>> memoryList = new java.util.ArrayList<>();
		for (Memory memory : metric.getMemoryList()) {
			Map<String, Object> memMap = new java.util.HashMap<>();
			memMap.put("isHeap", memory.getIsHeap());
			memMap.put("init", memory.getInit());
			memMap.put("max", memory.getMax());
			memMap.put("used", memory.getUsed());
			memMap.put("committed", memory.getCommitted());
			memoryList.add(memMap);
		}
		return memoryList;
	}

	private java.util.List<Map<String, Object>> buildMemoryPoolList(JVMMetric metric) {
		java.util.List<Map<String, Object>> memoryPoolList = new java.util.ArrayList<>();
		for (MemoryPool pool : metric.getMemoryPoolList()) {
			Map<String, Object> poolMap = new java.util.HashMap<>();
			poolMap.put("type", pool.getType());
			poolMap.put("init", pool.getInit());
			poolMap.put("max", pool.getMax());
			poolMap.put("used", pool.getUsed());
			poolMap.put("committed", pool.getCommitted());
			memoryPoolList.add(poolMap);
		}
		return memoryPoolList;
	}

	private java.util.List<Map<String, Object>> buildGcList(JVMMetric metric) {
		java.util.List<Map<String, Object>> gcList = new java.util.ArrayList<>();
		for (GC gc : metric.getGcList()) {
			Map<String, Object> gcMap = new java.util.HashMap<>();
			gcMap.put("phrase", gc.getPhase().name());
			gcMap.put("count", gc.getCount());
			gcMap.put("time", gc.getTime());
			gcList.add(gcMap);
		}
		return gcList;
	}

	private Map<String, Object> buildThreadMap(Thread thread) {
		Map<String, Object> threadMap = new java.util.HashMap<>();
		threadMap.put("daemon", thread.getDaemonCount()); // 守护线程数
		threadMap.put("live", thread.getLiveCount());     // 活跃线程数
		threadMap.put("peak", thread.getPeakCount());     // 峰值线程数
		threadMap.put("blocked", thread.getBlockedStateThreadCount());           // blocked状态线程数
		threadMap.put("runnable", thread.getRunnableStateThreadCount());         // runnable状态线程数
		threadMap.put("timed_waiting", thread.getTimedWaitingStateThreadCount()); // timed_waiting状态线程数
		threadMap.put("waiting", thread.getWaitingStateThreadCount());           // waiting状态线程数
		return threadMap;
	}

	private Map<String, Object> buildClassMap(Class clazz) {
		Map<String, Object> clazzMap = new java.util.HashMap<>();
		clazzMap.put("loaded", clazz.getLoadedClassCount());           // 当前已加载类数量
		clazzMap.put("total_loaded", clazz.getTotalLoadedClassCount()); // 累计加载类数量
		clazzMap.put("total_unloaded", clazz.getTotalUnloadedClassCount()); // 累计卸载类数量
		return clazzMap;
	}

	/**
	 * 按当前策略生成缓存 key 并写入本地 LRU Map。
	 */
	private void cacheMetrics(JVMMetricCollection jvmMetricCollection, Map<String, Object> metricMap) {
		// 以时间戳+实例名作为key，保证唯一性
		String cacheKey = jvmMetricCollection.getServiceInstance() + "_" + System.currentTimeMillis();
		jvmMetricsDataCache.put(cacheKey, metricMap);
	}

	@Override
	public void onComplete() {

	}

	@Override
	public void shutdown() {

	}
}