package org.apache.skywalking.apm.plugin.dynamic.override;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.jvm.JVMMetricsSender;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.network.language.agent.v3.GC;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetric;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetricCollection;
import org.apache.skywalking.apm.network.language.agent.v3.Memory;
import org.apache.skywalking.apm.network.language.agent.v3.MemoryPool;

/**
 * <p>
 * 收集到的JVM Metrics数据不再发送给OAP侧, 而是保存到本地.
 * <p>
 * <p>
 * Refer To {@code DisableJVMService}
 * <p>
 */
@OverrideImplementor(JVMMetricsSender.class)
public class JVMMetricsLocalSender extends JVMMetricsSender implements BootService, Runnable {
	private static final ILog LOGGER = LogManager.getLogger(JVMMetricsLocalSender.class);

	private LinkedBlockingQueue<JVMMetric> queue;

	private int maxMetricsDataSize = 1000;
	// 借鉴自Druid的JdbcDataSourceStat
	private LinkedHashMap<String, Map<String, Object>> jvmMetricsDataCache;

	@Override
	public void prepare() {
		queue = new LinkedBlockingQueue<>(Config.Jvm.BUFFER_SIZE);
		// ServiceManager.INSTANCE.findService(GRPCChannelManager.class).addChannelListener(this);

		jvmMetricsDataCache = new LinkedHashMap<String, Map<String, Object>>(16, 0.75f, false) {
			private static final long serialVersionUID = 1L;

			protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
				return (size() > maxMetricsDataSize);

			}
		};
	}

	@Override
	public void boot() {

	}

	public Collection<Map<String, Object>> getMetrics() {
		return jvmMetricsDataCache.values();
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
			JVMMetricCollection.Builder builder = JVMMetricCollection.newBuilder();
			LinkedList<JVMMetric> buffer = new LinkedList<>();
			queue.drainTo(buffer);
			if (buffer.size() > 0) {
				builder.addAllMetrics(buffer);
				builder.setService(Config.Agent.SERVICE_NAME);
				builder.setServiceInstance(Config.Agent.INSTANCE_NAME);
				JVMMetricCollection jvmMetricCollection = builder.build();
				// 将jvmMetricCollection转换为Map并存储到jvmMetricsDataCache中
				Map<String, Object> metricMap = new java.util.HashMap<>();
				metricMap.put("service", jvmMetricCollection.getService());
				metricMap.put("serviceInstance", jvmMetricCollection.getServiceInstance());
				// JVMMetric为protobuf对象，需逐个转换为Map
				java.util.List<Map<String, Object>> metricsList = new java.util.ArrayList<>();
				for (JVMMetric metric : jvmMetricCollection.getMetricsList()) {
					Map<String, Object> m = new java.util.HashMap<>();
					m.put("time", metric.getTime());
					m.put("cpu", metric.getCpu().getUsagePercent());

					// 将Memory对象列表转换为Map列表
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
					m.put("memory", memoryList);

					// 将MemoryPool对象列表转换为Map列表
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
					m.put("memoryPool", memoryPoolList);

					// 将GC对象列表转换为Map列表
					java.util.List<Map<String, Object>> gcList = new java.util.ArrayList<>();
					for (GC gc : metric.getGcList()) {
						Map<String, Object> gcMap = new java.util.HashMap<>();
						gcMap.put("phrase", gc.getPhase().name());
						gcMap.put("count", gc.getCount());
						gcMap.put("time", gc.getTime());
						gcList.add(gcMap);
					}
					m.put("gc", gcList);
					metricsList.add(m);
				}
				metricMap.put("metrics", metricsList);
				// 以时间戳+实例名作为key，保证唯一性
				String cacheKey = jvmMetricCollection.getServiceInstance() + "_" + System.currentTimeMillis();
				jvmMetricsDataCache.put(cacheKey, metricMap);

			}
		} catch (Throwable t) {
			LOGGER.error(t, "send JVM metrics to Collector fail.");
			// ServiceManager.INSTANCE.findService(GRPCChannelManager.class).reportError(t);
		}

	}

	@Override
	public void onComplete() {

	}

	@Override
	public void shutdown() {

	}
}