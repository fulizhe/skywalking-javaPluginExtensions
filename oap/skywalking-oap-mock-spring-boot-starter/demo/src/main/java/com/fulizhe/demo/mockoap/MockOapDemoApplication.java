package com.fulizhe.demo.mockoap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mock-OAP 演示应用的入口。
 *
 * <p>本进程同时承担两个角色:
 * <ul>
 * <li>被观测的 Spring Boot 业务应用(由 {@code -javaagent} 挂原生 SkyWalking agent 上报);</li>
 * <li>接收端(引入 {@code skywalking-oap-mock-spring-boot-starter},监听 11800)。</li>
 * </ul>
 *
 * <p>启动方式见 {@code demo/README.md};不挂 agent 时应用仍能启动,只是页面没有数据。
 */
@SpringBootApplication
public class MockOapDemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(MockOapDemoApplication.class, args);
	}
}
