package com.fulizhe.observability.mockoap;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.skywalking.apm.dependencies.io.grpc.Server;
import org.apache.skywalking.apm.dependencies.io.grpc.ServerServiceDefinition;
import org.apache.skywalking.apm.dependencies.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fulizhe.observability.mockoap.dynamicconfig.ConfigurationDiscoveryServiceImpl;
import com.fulizhe.observability.mockoap.jvmmetrics.JVMMetricBuffer;
import com.fulizhe.observability.mockoap.jvmmetrics.JVMMetricReportServiceImpl;
import com.fulizhe.observability.mockoap.log.LogBuffer;
import com.fulizhe.observability.mockoap.log.LogReportServiceImpl;
import com.fulizhe.observability.mockoap.trace.TraceSegmentBuffer;
import com.fulizhe.observability.mockoap.trace.TraceSegmentReportServiceImpl;

/**
 * 使用 SkyWalking agent 自带 shaded gRPC 栈的 collector 启动器(ADR-0001)。
 *
 * <p>本类是唯一引用 {@code org.apache.skywalking.apm.dependencies.io.grpc.*} 的地方。
 * 只有在确认 agent 运行时类可见后,{@link MockOapGrpcServer} 才会实例化它 ——
 * 避免「未挂 agent」时因类解析失败把 Spring 启动带崩。
 *
 * <p>为什么不直接用业务侧常见的 {@code io.grpc.*}:agent 的 grpc-1.x 插件会埋点
 * {@code io.grpc.internal.AbstractServerImplBuilder},把 collector 自己的 server 也变成埋点目标,
 * 从而形成「收段 -> 生成 span -> 再发段」的自激循环。
 */
class AgentShadedGrpcLauncher implements MockOapEmbeddedServer {

	private static final Logger log = LoggerFactory.getLogger(AgentShadedGrpcLauncher.class);

	/** SW 链路段单条可能较大(含 tag/stack),默认入站上限 16MB。 */
	private static final int DEFAULT_MAX_INBOUND_MESSAGE_SIZE = 16 * 1024 * 1024;

	private final int configuredPort;
	private final TraceSegmentBuffer buffer;
	private final LogBuffer logBuffer;
	private final JVMMetricBuffer jvmMetricBuffer;
	private final MockOapProperties properties;
	private final List<ServerServiceDefinition> additionalServices;
	private final int maxInboundMessageSize;

	private volatile Server server;
	private volatile TraceSegmentReportServiceImpl traceService;
	private volatile LogReportServiceImpl logService;
	private volatile JVMMetricReportServiceImpl jvmService;

	AgentShadedGrpcLauncher(int configuredPort, TraceSegmentBuffer buffer, LogBuffer logBuffer,
			JVMMetricBuffer jvmMetricBuffer) {
		this(configuredPort, buffer, logBuffer, jvmMetricBuffer, new MockOapProperties(),
				DEFAULT_MAX_INBOUND_MESSAGE_SIZE, new ServerServiceDefinition[0]);
	}

	AgentShadedGrpcLauncher(int configuredPort, TraceSegmentBuffer buffer, LogBuffer logBuffer,
			JVMMetricBuffer jvmMetricBuffer, int maxInboundMessageSize, ServerServiceDefinition... additionalServices) {
		this(configuredPort, buffer, logBuffer, jvmMetricBuffer, new MockOapProperties(),
				maxInboundMessageSize, additionalServices);
	}

	AgentShadedGrpcLauncher(int configuredPort, TraceSegmentBuffer buffer, LogBuffer logBuffer,
			JVMMetricBuffer jvmMetricBuffer, MockOapProperties properties, int maxInboundMessageSize,
			ServerServiceDefinition... additionalServices) {
		this.configuredPort = configuredPort;
		this.buffer = buffer;
		this.logBuffer = logBuffer;
		this.jvmMetricBuffer = jvmMetricBuffer;
		this.properties = properties;
		this.additionalServices = Arrays.asList(additionalServices);
		this.maxInboundMessageSize = maxInboundMessageSize > 0 ? maxInboundMessageSize : DEFAULT_MAX_INBOUND_MESSAGE_SIZE;
	}

	void start() throws IOException {
		log.info("### [mock-oap] starting native gRPC services: port={}, ingestQueueCapacity={}, ingestQueueMaxBytes={}",
				configuredPort, properties.getIngestQueueCapacity(), properties.getIngestQueueMaxBytes());
		TraceSegmentReportServiceImpl trace = new TraceSegmentReportServiceImpl(buffer, properties);
		LogReportServiceImpl logs = new LogReportServiceImpl(logBuffer, properties);
		JVMMetricReportServiceImpl jvm = new JVMMetricReportServiceImpl(jvmMetricBuffer, properties);
		this.traceService = trace;
		this.logService = logs;
		this.jvmService = jvm;
		org.apache.skywalking.apm.dependencies.io.grpc.netty.NettyServerBuilder builder = NettyServerBuilder.forPort(configuredPort)
				.maxInboundMessageSize(maxInboundMessageSize)
				.addService(trace)
				.addService(logs)
				.addService(new ConfigurationDiscoveryServiceImpl())
				.addService(jvm);
		for (ServerServiceDefinition service : additionalServices) {
			builder.addService(service);
		}
		try {
			server = builder.build().start();
		} catch (IOException | RuntimeException e) {
			close("trace", trace);
			close("log", logs);
			close("jvm-metrics", jvm);
			this.traceService = null;
			this.logService = null;
			this.jvmService = null;
			throw e;
		}
		log.info("### [mock-oap] gRPC server started on port {} (agent shaded gRPC; native trace + OTLP trace + log + configuration discovery + JVM metrics)", getPort());
	}

	@Override
	public int getPort() {
		Server s = this.server;
		return s != null ? s.getPort() : configuredPort;
	}

	@Override
	public boolean isRunning() {
		Server s = this.server;
		return s != null && !s.isShutdown();
	}

	@Override
	public Map<String, Object> ingestStats() {
		Map<String, Object> stats = new LinkedHashMap<String, Object>();
		TraceSegmentReportServiceImpl trace = this.traceService;
		LogReportServiceImpl logs = this.logService;
		JVMMetricReportServiceImpl jvm = this.jvmService;
		if (trace != null) stats.put("trace", trace.stats());
		if (logs != null) stats.put("log", logs.stats());
		if (jvm != null) stats.put("jvm-metrics", jvm.stats());
		return stats;
	}

	@Override
	public void stop() {
		log.info("### [mock-oap] stopping collector: running={}, traceService={}, logService={}, jvmService={}",
				isRunning(), traceService != null, logService != null, jvmService != null);
		Server s = this.server;
		if (s != null) {
			log.info("### [mock-oap] gracefully shutting down gRPC server");
			s.shutdown();
			try {
				if (!s.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
					log.warn("### [mock-oap] gRPC server did not drain within 5s; forcing shutdown");
					s.shutdownNow();
					if (!s.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
						log.warn("### [mock-oap] gRPC server still not terminated after forced shutdown");
					}
				}
			} catch (InterruptedException e) {
				s.shutdownNow();
				Thread.currentThread().interrupt();
				log.warn("### [mock-oap] gRPC server shutdown interrupted; forced down");
			}
			this.server = null;
		}
		close("trace", traceService);
		close("log", logService);
		close("jvm-metrics", jvmService);
		this.traceService = null;
		this.logService = null;
		this.jvmService = null;
		log.info("### [mock-oap] native collector stopped");
	}

	private static void close(String name, AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		log.info("### [mock-oap] closing {} ingest pipeline", name);
		try {
			closeable.close();
		} catch (Exception e) {
			log.warn("### [mock-oap] failed to close {} ingest pipeline: {}", name, e.getMessage());
		}
	}
}
