package com.fulizhe.observability.mockoap.jvmmetrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.skywalking.apm.dependencies.io.grpc.Status;
import org.apache.skywalking.apm.dependencies.io.grpc.stub.StreamObserver;
import org.apache.skywalking.apm.network.common.v3.Commands;
import org.apache.skywalking.apm.network.language.agent.v3.GC;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetricCollection;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetric;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetricReportServiceGrpc;
import org.apache.skywalking.apm.network.language.agent.v3.Memory;
import org.apache.skywalking.apm.network.language.agent.v3.MemoryPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fulizhe.observability.mockoap.MockOapPipelines;
import com.fulizhe.observability.mockoap.MockOapProperties;
import com.fulizhe.observability.mockoap.ingest.IngestLifecycle;
import com.fulizhe.observability.mockoap.ingest.IngestPipeline;
import com.fulizhe.observability.mockoap.ingest.IngestSubmitResult;

/**
 * SkyWalking 原生协议 {@code JVMMetricReportService.collect} 的最小实现。
 *
 * <p>mock-OAP 当前不保存 JVM 指标；收到上报后按批次节流记录，并返回空 {@link Commands}，
 * 表示没有命令下发。
 */
public class JVMMetricReportServiceImpl extends JVMMetricReportServiceGrpc.JVMMetricReportServiceImplBase
		implements IngestLifecycle {

	private static final Logger log = LoggerFactory.getLogger(JVMMetricReportServiceImpl.class);
	private static final long LOG_EVERY = 10L;

	private final AtomicLong requestCount = new AtomicLong();
	private final JVMMetricBuffer buffer;
	private final IngestPipeline<JVMMetricCollection> pipeline;

	public JVMMetricReportServiceImpl(JVMMetricBuffer buffer) {
		this(buffer, new MockOapProperties());
	}

	public JVMMetricReportServiceImpl(JVMMetricBuffer buffer, MockOapProperties properties) {
		this.buffer = buffer;
		this.pipeline = MockOapPipelines.start("jvm-metrics", properties, JVMMetricCollection::getSerializedSize,
				this::process);
	}

	@Override
	public void collect(JVMMetricCollection metrics, StreamObserver<Commands> responseObserver) {
		IngestSubmitResult submitted = pipeline.submit(metrics);
		if (submitted.isRejected()) {
			responseObserver.onError(Status.RESOURCE_EXHAUSTED
					.withDescription(IngestSubmitResult.REJECTION_DESCRIPTION).asRuntimeException());
			return;
		}
		responseObserver.onNext(Commands.getDefaultInstance());
		responseObserver.onCompleted();
	}

	private void process(JVMMetricCollection metrics) {
		long count = requestCount.incrementAndGet();
		MockOapJvmMetricRecord record = toRecord(metrics);
		buffer.add(record);
		if (count == 1 || count % LOG_EVERY == 0) {
			log.info("### [mock-oap] JVM metrics received: count={}, service={}, instance={}, metrics={}, "
					+ "buffer={}/{}",
					count, record.getService(), record.getServiceInstance(), record.getMetrics().size(),
					buffer.size(), buffer.capacity());
		}
	}

	@Override
	public IngestPipeline<JVMMetricCollection> ingestPipeline() {
		return pipeline;
	}

	/** 将协议消息转为可直接由 Spring MVC 序列化的普通 Java 记录。 */
	MockOapJvmMetricRecord toRecord(JVMMetricCollection collection) {
		MockOapJvmMetricRecord record = new MockOapJvmMetricRecord();
		record.setService(collection.getService());
		record.setServiceInstance(collection.getServiceInstance());
		record.setReceivedAt(System.currentTimeMillis());

		List<Map<String, Object>> metrics = new ArrayList<Map<String, Object>>(collection.getMetricsCount());
		for (JVMMetric metric : collection.getMetricsList()) {
			Map<String, Object> item = new LinkedHashMap<String, Object>();
			item.put("time", metric.getTime());
			if (metric.hasCpu()) {
				item.put("cpuUsagePercent", metric.getCpu().getUsagePercent());
			}
			List<Map<String, Object>> memory = new ArrayList<Map<String, Object>>(metric.getMemoryCount());
			for (Memory value : metric.getMemoryList()) {
				Map<String, Object> m = new LinkedHashMap<String, Object>();
				m.put("isHeap", value.getIsHeap());
				m.put("init", value.getInit());
				m.put("max", value.getMax());
				m.put("used", value.getUsed());
				m.put("committed", value.getCommitted());
				memory.add(m);
			}
			item.put("memory", memory);

			List<Map<String, Object>> pools = new ArrayList<Map<String, Object>>(metric.getMemoryPoolCount());
			for (MemoryPool value : metric.getMemoryPoolList()) {
				Map<String, Object> pool = new LinkedHashMap<String, Object>();
				pool.put("type", value.getType().name());
				pool.put("init", value.getInit());
				pool.put("max", value.getMax());
				pool.put("used", value.getUsed());
				pool.put("committed", value.getCommitted());
				pools.add(pool);
			}
			item.put("memoryPools", pools);

			List<Map<String, Object>> gc = new ArrayList<Map<String, Object>>(metric.getGcCount());
			for (GC value : metric.getGcList()) {
				Map<String, Object> collector = new LinkedHashMap<String, Object>();
				collector.put("phase", value.getPhase().name());
				collector.put("count", value.getCount());
				collector.put("time", value.getTime());
				gc.add(collector);
			}
			item.put("gc", gc);

			if (metric.hasThread()) {
				org.apache.skywalking.apm.network.language.agent.v3.Thread thread = metric.getThread();
				Map<String, Object> threads = new LinkedHashMap<String, Object>();
				threads.put("liveCount", thread.getLiveCount());
				threads.put("daemonCount", thread.getDaemonCount());
				threads.put("peakCount", thread.getPeakCount());
				threads.put("runnableStateThreadCount", thread.getRunnableStateThreadCount());
				threads.put("blockedStateThreadCount", thread.getBlockedStateThreadCount());
				threads.put("waitingStateThreadCount", thread.getWaitingStateThreadCount());
				threads.put("timedWaitingStateThreadCount", thread.getTimedWaitingStateThreadCount());
				item.put("thread", threads);
			}
			if (metric.hasClazz()) {
				org.apache.skywalking.apm.network.language.agent.v3.Class clazz = metric.getClazz();
				Map<String, Object> classes = new LinkedHashMap<String, Object>();
				classes.put("loadedClassCount", clazz.getLoadedClassCount());
				classes.put("totalUnloadedClassCount", clazz.getTotalUnloadedClassCount());
				classes.put("totalLoadedClassCount", clazz.getTotalLoadedClassCount());
				item.put("class", classes);
			}
			metrics.add(item);
		}
		record.setMetrics(metrics);
		return record;
	}
}
