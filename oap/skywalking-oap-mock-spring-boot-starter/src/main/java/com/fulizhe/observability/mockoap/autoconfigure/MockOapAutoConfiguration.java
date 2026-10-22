package com.fulizhe.observability.mockoap.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fulizhe.observability.mockoap.MockOapGrpcServer;
import com.fulizhe.observability.mockoap.MockOapProperties;
import com.fulizhe.observability.mockoap.jvmmetrics.JVMMetricBuffer;
import com.fulizhe.observability.mockoap.log.LogBuffer;
import com.fulizhe.observability.mockoap.otlp.OtlpTraceConverter;
import com.fulizhe.observability.mockoap.trace.TraceSegmentBuffer;

/**
 * Registers the in-process mock OAP collector.
 *
 * <p>The server bean is always created so an application can expose diagnostics even when
 * {@code mock-oap.enabled=false}. {@link MockOapGrpcServer#start()} performs the actual
 * enable/agent checks and skips listening without failing the application context.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MockOapProperties.class)
public class MockOapAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public TraceSegmentBuffer mockOapTraceSegmentBuffer(MockOapProperties properties) {
		return new TraceSegmentBuffer(properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public LogBuffer mockOapLogBuffer(MockOapProperties properties) {
		return new LogBuffer(properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public JVMMetricBuffer mockOapJvmMetricBuffer(MockOapProperties properties) {
		return new JVMMetricBuffer(properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public OtlpTraceConverter mockOapOtlpTraceConverter(MockOapProperties properties) {
		return new OtlpTraceConverter(properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public MockOapGrpcServer mockOapGrpcServer(MockOapProperties properties, TraceSegmentBuffer traceSegmentBuffer,
			LogBuffer logBuffer, JVMMetricBuffer jvmMetricBuffer, OtlpTraceConverter otlpTraceConverter) {
		return new MockOapGrpcServer(properties, traceSegmentBuffer, logBuffer, jvmMetricBuffer, otlpTraceConverter);
	}
}
