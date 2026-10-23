package com.fulizhe.demo.mockoap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.fulizhe.observability.mockoap.MockOapGrpcServer;

/**
 * 启动完成后把访问入口打到控制台最后,省得用户回头翻日志找地址。
 *
 * <p>注意:本类日志会经 {@code GRPCLogClientAppender} 再上报给 collector,
 * 所以不要用 {@code [mock-oap]} 前缀(那是采集器自身日志的拒收标记)。
 */
@Component
public class DemoReadyBanner {

	private static final Logger log = LoggerFactory.getLogger(DemoReadyBanner.class);

	private final int serverPort;
	private final int collectorPort;
	private final MockOapGrpcServer collector;

	public DemoReadyBanner(@Value("${server.port:9600}") int serverPort,
			@Value("${mock-oap.port:11800}") int collectorPort, MockOapGrpcServer collector) {
		this.serverPort = serverPort;
		this.collectorPort = collectorPort;
		this.collector = collector;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void printAccessInfo() {
		String base = "http://localhost:" + serverPort;
		String state = collector.isRunning() ? "running" : "skipped(" + collector.getSkipReason() + ")";
		log.info("\n"
				+ "============================================================\n"
				+ "  演示导航: " + base + "/\n"
				+ "  采集总览: " + base + "/mock-oap.html\n"
				+ "  日志检索: " + base + "/mock-oap-logs.html\n"
				+ "  JVM 指标: " + base + "/mock-oap-jvm.html\n"
				+ "  collector: 127.0.0.1:" + collectorPort + " " + state + "\n"
				+ "  提示: 启动后等约 30 秒再生成流量,agent 需要先连上 collector\n"
				+ "============================================================");
	}
}
