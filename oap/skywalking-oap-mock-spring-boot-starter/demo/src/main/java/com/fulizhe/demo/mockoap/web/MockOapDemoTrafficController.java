package com.fulizhe.demo.mockoap.web;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * 制造演示流量:让本进程调用自己,从而在页面上产生可看的 trace / span / 日志。
 *
 * <p>所有接口都在同一个 JVM 内,不需要额外的 go / node 服务:
 * <ul>
 *   <li>{@code /demo/chain} — Apache HttpClient 调本进程的 {@code /demo/leaf},
 *       由 agent 生成 Exit span 并把 sw8 传给下一跳,得到一条多段链路;</li>
 *   <li>{@code /demo/slow} — 睡眠制造慢 span;</li>
 *   <li>{@code /demo/error} — 抛异常制造错误 span。</li>
 * </ul>
 *
 * <p>这些请求本身也会被 agent 记录,所以点开页面、刷新数据同样会出现在链路清单里。
 *
 * <p>日志通过 logback 的 {@code GRPCLogClientAppender} 上报,正文带当前 traceId。
 * 注意:采集器会拒收正文包含 {@code [mock-oap]} 或 {@code com.fulizhe.observability.mockoap}
 * 的日志(防止采集器自己的日志形成放大回路),所以本类日志统一用 {@code [demo]} 前缀。
 */
@RestController
@RequestMapping("/demo")
public class MockOapDemoTrafficController {

	private static final Logger log = LoggerFactory.getLogger(MockOapDemoTrafficController.class);

	private static final int MAX_STEPS = 20;

	private final CloseableHttpClient httpClient = HttpClients.createDefault();

	private final String selfBaseUrl;

	public MockOapDemoTrafficController(@Value("${server.port:9600}") int serverPort) {
		this.selfBaseUrl = "http://127.0.0.1:" + serverPort;
	}

	/**
	 * 生成一条"自己调自己"的链路。
	 *
	 * @param steps 顺序发起的下游调用次数(1~20)
	 * @param delayMs 每次下游调用在叶子接口停留的毫秒数
	 */
	@GetMapping("/chain")
	public Map<String, Object> chain(@RequestParam(value = "steps", required = false, defaultValue = "3") int steps,
			@RequestParam(value = "delayMs", required = false, defaultValue = "0") int delayMs) throws Exception {
		int hops = Math.min(Math.max(steps, 1), MAX_STEPS);
		String orderId = "DEMO-" + System.currentTimeMillis();
		long begin = System.currentTimeMillis();
		log.info("[demo] 链路演示开始: orderId={}, steps={}, delayMs={}", orderId, hops, delayMs);

		List<Map<String, Object>> calls = new ArrayList<Map<String, Object>>(hops);
		for (int i = 1; i <= hops; i++) {
			String url = selfBaseUrl + "/demo/leaf?orderId=" + orderId + "&hop=" + i + "&delayMs=" + delayMs;
			try (CloseableHttpResponse resp = httpClient.execute(new HttpGet(url))) {
				int status = resp.getStatusLine().getStatusCode();
				String body = resp.getEntity() == null ? null
						: EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
				Map<String, Object> call = new LinkedHashMap<String, Object>();
				call.put("hop", i);
				call.put("url", url);
				call.put("status", status);
				call.put("body", body);
				calls.add(call);
			}
		}

		long cost = System.currentTimeMillis() - begin;
		log.info("[demo] 链路演示完成: orderId={}, steps={}, 耗时 {}ms", orderId, hops, cost);

		Map<String, Object> result = new LinkedHashMap<String, Object>();
		result.put("orderId", orderId);
		result.put("steps", hops);
		result.put("delayMs", delayMs);
		result.put("costMs", cost);
		result.put("calls", calls);
		return result;
	}

	/** 链路的叶子节点:每个 hop 一次,产生一个 Entry span 和一条业务日志。 */
	@GetMapping("/leaf")
	public Map<String, Object> leaf(@RequestParam(value = "orderId", required = false, defaultValue = "-") String orderId,
			@RequestParam(value = "hop", required = false, defaultValue = "1") int hop,
			@RequestParam(value = "delayMs", required = false, defaultValue = "0") int delayMs)
			throws InterruptedException {
		if (delayMs > 0) {
			Thread.sleep(delayMs);
		}
		log.info("[demo] 叶子调用: orderId={}, hop={}, delayMs={}", orderId, hop, delayMs);

		Map<String, Object> result = new LinkedHashMap<String, Object>();
		result.put("orderId", orderId);
		result.put("hop", hop);
		result.put("delayMs", delayMs);
		result.put("thread", Thread.currentThread().getName());
		return result;
	}

	/** 慢调用:睡眠指定毫秒,制造一个耗时明显的 span。 */
	@GetMapping("/slow")
	public Map<String, Object> slow(@RequestParam(value = "delayMs", required = false, defaultValue = "500") int delayMs)
			throws InterruptedException {
		int sleep = Math.min(Math.max(delayMs, 0), 30000);
		log.info("[demo] 慢调用开始: delayMs={}", sleep);
		Thread.sleep(sleep);
		log.info("[demo] 慢调用结束: delayMs={}", sleep);

		Map<String, Object> result = new LinkedHashMap<String, Object>();
		result.put("delayMs", sleep);
		return result;
	}

	/** 错误调用:抛异常,制造一个 ERROR span 和一条 ERROR 日志。 */
	@GetMapping("/error")
	public Map<String, Object> error() {
		log.error("[demo] 错误调用: 这是一次故意抛出的异常");
		throw new IllegalStateException("mock-oap demo: intentional failure");
	}
}
