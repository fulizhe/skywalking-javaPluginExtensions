package com.fulizhe.demo.mockoap.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨语言链路入口:本进程(Java)作为入口去调 go-service,把
 * {@code java -> go -> cpp -> node} 串成同一条 trace。
 *
 * <p>和本进程自调自的 {@link MockOapDemoTrafficController#chain} 不同,这条链路里每一跳都是
 * 另一个语言实现的真实服务(Go 用 skywalking-go、C++ 用 OpenTelemetry SDK、Node 用
 * skywalking-backend-js),四段靠 {@code sw8} 上下文串起来,最终都上报到本进程内的 mock-OAP。
 *
 * <p>Java 侧只需要一个普通 HTTP 出口:agent 默认的 {@code httpclient-4.x} 插件会自动建
 * Exit span 并注入 {@code sw8},栈里零 SkyWalking API。
 *
 * <p>日志统一用 {@code [demo]} 前缀 —— 采集器会拒收正文含 {@code [mock-oap]} 或
 * {@code com.fulizhe.observability.mockoap} 的日志(防放大回路)。
 */
@RestController
@RequestMapping("/mock-oap/demo")
public class MockOapChainDemoController {

	private static final Logger log = LoggerFactory.getLogger(MockOapChainDemoController.class);

	/** hops=3 才走满 go -> cpp -> node 三跳;再大没有下游了。 */
	private static final int MAX_HOPS = 3;

	private final CloseableHttpClient httpClient = HttpClients.createDefault();

	private final String goServiceUrl;

	public MockOapChainDemoController(
			@Value("${demo.go-service-url:http://go-service:8080}") String goServiceUrl) {
		this.goServiceUrl = goServiceUrl.endsWith("/")
				? goServiceUrl.substring(0, goServiceUrl.length() - 1)
				: goServiceUrl;
	}

	/**
	 * 触发一条跨语言链路。
	 *
	 * @param hops 转发几跳(1~3):1=只到 go,2=go->cpp,3=go->cpp->node
	 * @param latency 每一跳模拟的耗时(毫秒)
	 */
	@GetMapping("/go-chain")
	public Map<String, Object> goChain(
			@RequestParam(value = "hops", required = false, defaultValue = "3") int hops,
			@RequestParam(value = "latency", required = false, defaultValue = "20") int latency) throws Exception {
		int effectiveHops = Math.min(Math.max(hops, 1), MAX_HOPS);
		String orderId = "JAVA-" + System.currentTimeMillis();
		String url = goServiceUrl + "/api/forward?hops=" + effectiveHops + "&latency=" + latency
				+ "&orderId=" + URLEncoder.encode(orderId, StandardCharsets.UTF_8.name());
		long begin = System.currentTimeMillis();

		log.info("[demo] 跨语言链路开始: orderId={}, url={}, hops={}, latency={}",
				orderId, url, effectiveHops, latency);

		Map<String, Object> result = new LinkedHashMap<String, Object>();
		result.put("orderId", orderId);
		result.put("targetUrl", url);
		try (CloseableHttpResponse resp = httpClient.execute(new HttpGet(url))) {
			int status = resp.getStatusLine().getStatusCode();
			String body = resp.getEntity() == null ? null
					: EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
			long cost = System.currentTimeMillis() - begin;
			if (status >= 400) {
				log.warn("[demo] 跨语言链路下游返回 HTTP {}, orderId={}, 耗时 {}ms, body={}",
						status, orderId, cost, body);
			} else {
				log.info("[demo] 跨语言链路完成: HTTP {}, orderId={}, 耗时 {}ms, body={}",
						status, orderId, cost, body);
			}
			result.put("status", status);
			result.put("body", body);
			result.put("costMs", cost);
		}
		return result;
	}
}
