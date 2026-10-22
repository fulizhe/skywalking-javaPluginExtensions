package com.fulizhe.observability.mockoap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mock-OAP 采集配置(前缀 {@code mock-oap})。
 *
 * <p>默认端口 11800 与 SkyWalking agent 默认 {@code collector.backend_service} 一致——
 * 各语言 agent 按标准 SW 接入方式指向本进程即可,客户端零自定义。
 *
 * <p>Starter 默认不启用,避免业务系统引入依赖后意外占用端口。需要显式设置
 * {@code mock-oap.enabled=true} 才会监听。
 */
@ConfigurationProperties(prefix = "mock-oap")
public class MockOapProperties {

	/** 是否启用进程内 mock-OAP gRPC server。 */
	private boolean enabled = false;

	/** 监听端口;默认与 SW agent 默认上报端口一致。 */
	private int port = 11800;

	/** 进程内保留的最近链路段条数(超出按 FIFO 淘汰)。 */
	private int maxTraces = 200;

	/** 进程内保留的最近日志条数(超出按 FIFO 淘汰);日志与链路段各自独立计数。 */
	private int maxLogs = 2000;

	/** 进程内保留的最近 JVM 指标批次数(超出按 FIFO 淘汰)。 */
	private int maxJvmMetrics = 2000;

	/** 单个信号 ingress 队列的条数上限。 */
	private int ingestQueueCapacity = 2048;

	/** 单个信号 ingress 队列的估算字节上限。 */
	private long ingestQueueMaxBytes = 64L * 1024 * 1024;

	/** 上报单次 OTLP Export 接受的最大 Span 数。 */
	private int maxOtlpSpansPerRequest = 10000;
	private int maxOtlpAttributesPerSpan = 128;
	private int maxOtlpEventsPerSpan = 128;
	private int maxOtlpLinksPerSpan = 128;
	private int maxOtlpStringLength = 4096;
	private int maxOtlpInboundMessageBytes = 16 * 1024 * 1024;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public int getMaxTraces() {
		return maxTraces;
	}

	public void setMaxTraces(int maxTraces) {
		this.maxTraces = maxTraces;
	}

	public int getMaxLogs() {
		return maxLogs;
	}

	public void setMaxLogs(int maxLogs) {
		this.maxLogs = maxLogs;
	}

	public int getMaxJvmMetrics() {
		return maxJvmMetrics;
	}

	public void setMaxJvmMetrics(int maxJvmMetrics) {
		this.maxJvmMetrics = maxJvmMetrics;
	}

	public int getIngestQueueCapacity() { return ingestQueueCapacity; }
	public void setIngestQueueCapacity(int value) { this.ingestQueueCapacity = value; }
	public long getIngestQueueMaxBytes() { return ingestQueueMaxBytes; }
	public void setIngestQueueMaxBytes(long value) { this.ingestQueueMaxBytes = value; }

	public int getMaxOtlpSpansPerRequest() { return maxOtlpSpansPerRequest; }
	public void setMaxOtlpSpansPerRequest(int value) { this.maxOtlpSpansPerRequest = value; }
	public int getMaxOtlpAttributesPerSpan() { return maxOtlpAttributesPerSpan; }
	public void setMaxOtlpAttributesPerSpan(int value) { this.maxOtlpAttributesPerSpan = value; }
	public int getMaxOtlpEventsPerSpan() { return maxOtlpEventsPerSpan; }
	public void setMaxOtlpEventsPerSpan(int value) { this.maxOtlpEventsPerSpan = value; }
	public int getMaxOtlpLinksPerSpan() { return maxOtlpLinksPerSpan; }
	public void setMaxOtlpLinksPerSpan(int value) { this.maxOtlpLinksPerSpan = value; }
	public int getMaxOtlpStringLength() { return maxOtlpStringLength; }
	public void setMaxOtlpStringLength(int value) { this.maxOtlpStringLength = value; }
	public int getMaxOtlpInboundMessageBytes() { return maxOtlpInboundMessageBytes; }
	public void setMaxOtlpInboundMessageBytes(int value) { this.maxOtlpInboundMessageBytes = value; }
}
