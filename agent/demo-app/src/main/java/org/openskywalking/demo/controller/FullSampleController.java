/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openskywalking.demo.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.skywalking.apm.toolkit.trace.ActiveSpan;
import org.apache.skywalking.apm.toolkit.trace.Tag;
import org.apache.skywalking.apm.toolkit.trace.Trace;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import org.openskywalking.demo.service.HelloService;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;

import io.swagger.annotations.ApiOperation;

/**
 * 「全栈样例」：一条 {@code /fullSample} 请求在一个 traceId 下串起尽可能多的组件，
 * 用来观察**多 span / 多 segment** 的完整链路。
 * <p>
 * 覆盖：<b>tomcat</b>（本请求即 Entry span）→ <b>MyBatis</b> → <b>JDBC</b> →
 * <b>HTTP 出口</b>（Apache HttpClient 自调用，Exit span + 第二个 segment）→ 日志/注解。
 * </p>
 * <p>
 * 保持 demo 现状、不额外引依赖：<b>redis</b> 未引入依赖/实例故暂不接入；
 * <b>hutool http</b> 仅有 hutool-core/json（无 hutool-http），HTTP 出口统一用 Apache HttpClient。
 * </p>
 */
@RestController
public class FullSampleController {

	private static final Logger log = LoggerFactory.getLogger(FullSampleController.class);

	@Autowired
	private HelloService helloService;

	@Autowired
	private CloseableHttpClient skyWalkingDemoHttpClient;

	// 文档： https://skywalking.apache.org/docs/skywalking-java/v9.4.0/en/setup/service-agent/java-agent/application-toolkit-trace-annotation/
	@Trace
	@Tag(key = "tag1", value = "arg[0]")
	@ApiOperation(value = "fullSample", notes = "full Sample：tomcat(Entry) + MyBatis + JDBC + HTTP 出口(Exit) + 日志/注解")
	@ApiOperationSupport(order = 1)
	@GetMapping("/fullSample")
	public Map<String, Object> fullSample(HttpServletRequest request) throws IOException {
		final Map<String, Object> result = new LinkedHashMap<String, Object>();

		// ① tomcat 入口：本次请求即 Entry span；补一条打点便于在链路里定位
		ActiveSpan.info("### fullSample start (tomcat entry)");
		result.put("entry", "tomcat (GET /fullSample)");

		// ② MyBatis 查询（产生 db 出口 span）
		result.put("mybatis", helloService.dbByMybatis());

		// ③ JDBC 直查（产生 db 出口 span）
		result.put("jdbc", helloService.queryDbByJdbc());

		// ④ HTTP 出口：自调用本应用端点，Exit span + 第二个 segment（同一 traceId）
		result.put("http", selfCall(request));

		// ⑤ redis：demo 未引入 redis 依赖/实例，暂不接入（见类注释）
		result.put("redis", "skipped (demo has no redis dependency/instance)");

		// ⑥ 日志 / 注解打点
		ActiveSpan.info("### for test log data");
		log.info("### fullSample done, traceId={}", TraceContext.traceId());

		result.put("service", helloService.sayHello());
		result.put("traceId", TraceContext.traceId());
		result.put("segmentId", TraceContext.segmentId());
		result.put("hint", "链路应含：Entry(tomcat) + @Trace(Local) + MyBatis/JDBC(Exit) + HttpClient(Exit) + 第二段 Entry(/api/trace-alert-demo/ok)");
		return result;
	}

	/** 经 Apache HttpClient 自调用 {@code /api/trace-alert-demo/ok}（本机端口取自当前请求），产生 Exit span + 第二个 segment。 */
	private Map<String, Object> selfCall(HttpServletRequest request) throws IOException {
		final String targetUrl = "http://127.0.0.1:" + request.getLocalPort() + "/api/trace-alert-demo/ok";
		final Map<String, Object> out = new LinkedHashMap<String, Object>();
		final HttpGet get = new HttpGet(targetUrl);
		try (CloseableHttpResponse response = skyWalkingDemoHttpClient.execute(get)) {
			out.put("client", "apache-httpclient");
			out.put("targetUrl", targetUrl);
			out.put("httpStatus", response.getStatusLine().getStatusCode());
			if (response.getEntity() != null) {
				final String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
				out.put("bodySnippet", body.length() > 200 ? body.substring(0, 200) + "..." : body);
			}
		}
		return out;
	}
}