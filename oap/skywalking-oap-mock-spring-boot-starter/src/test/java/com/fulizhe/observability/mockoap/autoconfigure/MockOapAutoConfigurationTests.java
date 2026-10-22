package com.fulizhe.observability.mockoap.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fulizhe.observability.mockoap.MockOapGrpcServer;
import com.fulizhe.observability.mockoap.MockOapProperties;
import com.fulizhe.observability.mockoap.jvmmetrics.JVMMetricBuffer;
import com.fulizhe.observability.mockoap.log.LogBuffer;
import com.fulizhe.observability.mockoap.trace.TraceSegmentBuffer;

public class MockOapAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(MockOapAutoConfiguration.class));

	@Test
	public void disabledByDefaultStillProvidesDiagnosticsBeans() {
		runner.run(context -> {
			assertThat(context).hasSingleBean(MockOapProperties.class);
			assertThat(context).hasSingleBean(TraceSegmentBuffer.class);
			assertThat(context).hasSingleBean(LogBuffer.class);
			assertThat(context).hasSingleBean(JVMMetricBuffer.class);
			assertThat(context).hasSingleBean(MockOapGrpcServer.class);
			assertThat(context.getBean(MockOapProperties.class).isEnabled()).isFalse();
			assertThat(context.getBean(MockOapGrpcServer.class).isRunning()).isFalse();
		});
	}

	@Test
	public void bindsConfigurationAndCreatesOneServer() {
		runner.withPropertyValues("mock-oap.enabled=false", "mock-oap.max-traces=7")
				.run(context -> {
					assertThat(context).hasSingleBean(MockOapGrpcServer.class);
					assertThat(context.getBean(MockOapProperties.class).getMaxTraces()).isEqualTo(7);
				});
	}

	@Test
	public void enabledWithoutAgentSkipsButKeepsApplicationContextHealthy() {
		runner.withPropertyValues("mock-oap.enabled=true").run(context -> {
			MockOapGrpcServer server = context.getBean(MockOapGrpcServer.class);
			assertThat(server.isRunning()).isFalse();
			assertThat(server.getSkipReason()).isNotBlank();
		});
	}

	@Test
	public void springFactoriesDeclaresAutoConfiguration() throws Exception {
		Properties factories = new Properties();
		try (InputStream in = getClass().getClassLoader()
				.getResourceAsStream("META-INF/spring.factories")) {
			assertThat(in).isNotNull();
			factories.load(in);
		}
		assertThat(factories.getProperty("org.springframework.boot.autoconfigure.EnableAutoConfiguration"))
				.contains(MockOapAutoConfiguration.class.getName());
	}
}
