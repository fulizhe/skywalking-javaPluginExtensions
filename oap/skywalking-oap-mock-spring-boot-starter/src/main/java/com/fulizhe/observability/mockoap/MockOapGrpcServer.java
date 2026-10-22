package com.fulizhe.observability.mockoap;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fulizhe.observability.mockoap.jvmmetrics.JVMMetricBuffer;
import com.fulizhe.observability.mockoap.log.LogBuffer;
import com.fulizhe.observability.mockoap.otlp.OtlpTraceConverter;
import com.fulizhe.observability.mockoap.otlp.OtlpTraceServiceBinding;
import com.fulizhe.observability.mockoap.otlp.OtlpTraceServiceImpl;
import com.fulizhe.observability.mockoap.trace.TraceSegmentBuffer;

/**
 * 进程内 mock-OAP gRPC server(glowroot 式:采集服务与业务同进程)。
 *
 * <p>按 ADR-0001:{@link AgentShadedGrpcLauncher} 复用 SkyWalking agent 自带的 shaded gRPC 栈,
 * 使 agent 的 grpc-1.x 插件不会埋点本 server(否则形成自激循环);业务侧未 shaded 的 gRPC
 * 调用仍照常被追踪。
 *
 * <p>shaded 类由 {@code -javaagent} 追加到 system classpath。未检测到时**优雅跳过**:
 * 应用照常启动,collector 不监听端口,只打一条 WARN;检测通过后才实例化 launcher
 * (该类才引用 shaded 类型),避免 {@code NoClassDefFoundError} 带崩 Spring 启动。
 */
public class MockOapGrpcServer {

	private static final Logger log = LoggerFactory.getLogger(MockOapGrpcServer.class);

	/** 用于判断 agent 运行时是否在 classpath 上(由 -javaagent 提供)。 */
	private static final String AGENT_GRPC_SERVER_CLASS = "org.apache.skywalking.apm.dependencies.io.grpc.Server";

	/** agent 启动时会把服务名写进这个内部配置类;只有 classpath 上放着 jar(没挂 -javaagent)时它是空值。 */
	private static final String AGENT_CONFIG_AGENT_CLASS = "org.apache.skywalking.apm.agent.core.conf.Config$Agent";

	private final MockOapProperties properties;
	private final TraceSegmentBuffer buffer;
	private final LogBuffer logBuffer;
	private final JVMMetricBuffer jvmMetricBuffer;
	private final OtlpTraceConverter otlpTraceConverter;
	private volatile OtlpTraceServiceImpl otlpTraceService;

	private volatile MockOapEmbeddedServer launcher;
	private volatile boolean running;
	private volatile boolean agentClassesVisible;
	private volatile boolean agentBooted;
	private volatile String skipReason;

	public MockOapGrpcServer(MockOapProperties properties, TraceSegmentBuffer buffer, LogBuffer logBuffer,
			JVMMetricBuffer jvmMetricBuffer, OtlpTraceConverter otlpTraceConverter) {
		this.properties = properties;
		this.buffer = buffer;
		this.logBuffer = logBuffer;
		this.jvmMetricBuffer = jvmMetricBuffer;
		this.otlpTraceConverter = otlpTraceConverter;
	}

	public MockOapGrpcServer(MockOapProperties properties, TraceSegmentBuffer buffer, LogBuffer logBuffer,
			JVMMetricBuffer jvmMetricBuffer) {
		this(properties, buffer, logBuffer, jvmMetricBuffer, new OtlpTraceConverter());
	}

	@PostConstruct
	public void start() {
		if (!properties.isEnabled()) {
			skipReason = "mock-oap.enabled=false";
			log.info("### [mock-oap] disabled (mock-oap.enabled=false), skip gRPC server");
			return;
		}
		if (!isClassVisible(AGENT_GRPC_SERVER_CLASS)) {
			agentClassesVisible = false;
			skipReason = "SkyWalking agent runtime not found: " + AGENT_GRPC_SERVER_CLASS
					+ " (start the app with -javaagent:<agent>/skywalking-agent.jar)";
			log.warn("### [mock-oap] 未检测到 SkyWalking agent 运行时,collector 已跳过(功能不启用)。"
					+ " 如需启用,请用 -javaagent:<agent>/skywalking-agent.jar 启动。class={}",
					AGENT_GRPC_SERVER_CLASS);
			return;
		}
		agentClassesVisible = true;

		if (!checkAgentBooted()) {
			agentBooted = false;
			skipReason = "SkyWalking agent classes are visible, but the agent is NOT running (premain did not execute)."
					+ " A copy of skywalking-agent.jar on the application classpath is not enough —"
					+ " the agent then resolves its home to that copy and loads zero plugins."
					+ " Start the app with -javaagent:<agent>/skywalking-agent.jar.";
			log.warn("### [mock-oap] agent 的类在 classpath 上可见,但 agent 并没有启动(premain 未执行),"
					+ "collector 已跳过。把 agent jar 放进应用 classpath 是无效的:agent 会把自己的 home"
					+ " 认成该 jar 所在目录,从而加载 0 个插件。请用 -javaagent:<agent>/skywalking-agent.jar 启动。");
			return;
		}
		agentBooted = true;

		try {
			log.info("### [mock-oap] starting collector on port {} (ingestQueueCapacity={}, ingestQueueMaxBytes={})",
					properties.getPort(), properties.getIngestQueueCapacity(), properties.getIngestQueueMaxBytes());
			OtlpTraceServiceImpl otlpService = new OtlpTraceServiceImpl(buffer, otlpTraceConverter, properties);
			this.otlpTraceService = otlpService;
			AgentShadedGrpcLauncher l = new AgentShadedGrpcLauncher(properties.getPort(), buffer, logBuffer,
					jvmMetricBuffer, properties, properties.getMaxOtlpInboundMessageBytes(),
					new OtlpTraceServiceBinding(otlpService).definition());
			l.start();
			this.launcher = l;
			this.running = true;
		} catch (Throwable t) {
			this.running = false;
			this.skipReason = "failed to start collector: " + t;
			log.error("### [mock-oap] collector 启动失败: {}", t.toString(), t);
			OtlpTraceServiceImpl otlpService = this.otlpTraceService;
			if (otlpService != null) {
				otlpService.close();
				this.otlpTraceService = null;
			}
		}
	}

	@PreDestroy
	public void stop() {
		log.info("### [mock-oap] stopping collector: running={}, launcher={}, otlpService={}",
				running, launcher != null, otlpTraceService != null);
		MockOapEmbeddedServer l = this.launcher;
		if (l != null) {
			try {
				l.stop();
			} catch (RuntimeException e) {
				log.error("### [mock-oap] native collector stop failed", e);
			} finally {
				this.launcher = null;
			}
		}
		OtlpTraceServiceImpl otlpService = this.otlpTraceService;
		if (otlpService != null) {
			log.info("### [mock-oap] closing OTLP ingest pipeline");
			try {
				otlpService.close();
			} catch (RuntimeException e) {
				log.error("### [mock-oap] OTLP pipeline close failed", e);
			} finally {
				this.otlpTraceService = null;
			}
		}
		this.running = false;
		log.info("### [mock-oap] collector stopped");
	}

	public Map<String, Object> getIngestStats() {
		Map<String, Object> stats = new LinkedHashMap<String, Object>();
		MockOapEmbeddedServer l = this.launcher;
		if (l != null) {
			stats.putAll(l.ingestStats());
		}
		OtlpTraceServiceImpl otlpService = this.otlpTraceService;
		if (otlpService != null) {
			stats.put("otlp", otlpService.stats());
		}
		return stats;
	}

	private boolean isClassVisible(String className) {
		try {
			Class.forName(className, false, MockOapGrpcServer.class.getClassLoader());
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * agent 是否真的跑起来了(premain 执行、读到了 service_name)。
	 *
	 * <p>只看"类可见"不够:把 agent jar 放进应用 classpath 也会让类可见,而 agent 本身并没启动。
	 * 这里读 agent 启动时写入的配置(未挂 agent 时为空);读不到就当作已启动(fail-open),避免误伤。
	 */
	private boolean checkAgentBooted() {
		try {
			Class<?> agentConfig = Class.forName(AGENT_CONFIG_AGENT_CLASS, false, MockOapGrpcServer.class.getClassLoader());
			Object serviceName = agentConfig.getField("SERVICE_NAME").get(null);
			return serviceName instanceof String && !((String) serviceName).isEmpty();
		} catch (Throwable t) {
			return true;
		}
	}

	public boolean isRunning() {
		MockOapEmbeddedServer l = this.launcher;
		return running && l != null && l.isRunning();
	}

	public int getPort() {
		MockOapEmbeddedServer l = this.launcher;
		return l != null ? l.getPort() : properties.getPort();
	}

	/** agent 运行时类是否可见(-javaagent 提供)。 */
	public boolean isAgentClassesVisible() {
		return agentClassesVisible;
	}

	/** agent 是否真的启动过(premain 执行、读到 service_name);仅"jar 在 classpath 上"不算。 */
	public boolean isAgentBooted() {
		return agentBooted;
	}

	/** 未启用/启动失败的原因;正常运行时为 null。 */
	public String getSkipReason() {
		return skipReason;
	}

	public long getOtlpAcceptedSpans() {
		OtlpTraceServiceImpl service = otlpTraceService;
		return service == null ? 0 : service.acceptedSpanTotal();
	}

	public long getOtlpRejectedSpans() {
		OtlpTraceServiceImpl service = otlpTraceService;
		return service == null ? 0 : service.rejectedSpanTotal();
	}

	public long getOtlpQueueEvictedSpans() {
		OtlpTraceServiceImpl service = otlpTraceService;
		return service == null ? 0 : service.queueEvictedSpanTotal();
	}

	public long getOtlpLostSpans() {
		OtlpTraceServiceImpl service = otlpTraceService;
		return service == null ? 0 : service.lostSpanTotal();
	}
}
