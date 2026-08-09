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

import javax.servlet.http.HttpServletResponse;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trace 告警验证触发端点(移植自旧项目 sb-skywalking 的 TraceAlertVerifyController):
 * 慢请求、HTTP 错误、忽略规则(白名单)场景,配合 agent Trace Alert 验证。
 * <p>
 * 与随附的 agent.trace-alert.config.sample 规则对应(注意:Spring MVC 端点 operation 名带方法前缀,规则须写全 METHOD):
 * <pre>
 *   slow_rules:        operation:GET:/status/*=8000;operation:GET:/api/order/*=8000;operation:GET:/api/export/**=60000
 *   error_ignore_rules:operation:GET:/.well-known/**=404;operation:GET:/status/*=503,500,400;
 *                      operation:GET:/api/exists/*=404;operation:GET:/inner/business-test/**=404,410
 * </pre>
 */
@RestController
public class TraceAlertVerifyController {

    private static final String HTTPBIN_STATUS_URL = "https://httpbin.org/status/";

    @Autowired
    private CloseableHttpClient skyWalkingDemoHttpClient;

    /** HttpClient 调 httpbin 指定状态码(默认 400):验证客户端 exit span 是否触发 trace 级 ERROR */
    @GetMapping("/api/trace-alert-demo/httpclient-httpbin")
    public Map<String, Object> httpClientHttpbin(@RequestParam(defaultValue = "400") int status) throws IOException {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be 100-599");
        }
        String targetUrl = HTTPBIN_STATUS_URL + status;
        HttpGet get = new HttpGet(targetUrl);
        int httpStatus;
        String bodySnippet;
        try (CloseableHttpResponse response = skyWalkingDemoHttpClient.execute(get)) {
            httpStatus = response.getStatusLine().getStatusCode();
            if (response.getEntity() == null) {
                bodySnippet = "";
            } else {
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                bodySnippet = body.length() > 200 ? body.substring(0, 200) + "..." : body;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("client", "apache-httpclient");
        result.put("targetUrl", targetUrl);
        result.put("httpStatus", httpStatus);
        result.put("bodySnippet", bodySnippet);
        // 与默认 plugin.logfilereporter.alert.http_error_status_min=500 对齐
        result.put("expectedTraceErrorByHttpStatus", httpStatus >= 500);
        result.put("verificationHint",
                httpStatus >= 500
                        ? "默认配置下应触发 ERROR(http.status_code >= 500);Webhook alertTypes 应含 ERROR"
                        : "默认配置下不应因 http.status_code 触发 ERROR(< 500);若仍收到 ERROR 告警请检查 span.isError 或其它规则");
        return result;
    }

    /** 慢请求:可指定休眠毫秒数(默认超过默认阈值 3000) */
    @GetMapping("/api/trace-alert-demo/slow")
    public String slow(@RequestParam(defaultValue = "4000") int ms) {
        sleep(ms);
        return "slow:" + ms;
    }

    /** 慢请求:匹配 slow_rules operation:GET:/api/order/*(Ant) */
    @GetMapping("/api/order/{id}")
    public String order(@PathVariable String id) {
        sleep(8500);
        return "order-" + id;
    }

    /** 慢请求:匹配 slow_rules operation:GET:/api/export/** (Ant) */
    @GetMapping("/api/export/report")
    public String exportReport(@RequestParam(defaultValue = "65000") int ms) {
        sleep(ms);
        return "export-done:" + ms;
    }

    /** 慢/错白名单:operation:/status/*(Ant);?ms=8500 测慢,路径段为 HTTP 状态码 */
    @GetMapping("/status/{code}")
    public Map<String, Object> status(@PathVariable int code, @RequestParam(defaultValue = "0") int ms,
            HttpServletResponse response) {
        if (code < 100 || code > 599) {
            throw new IllegalArgumentException("code must be 100-599");
        }
        if (ms > 0) {
            sleep(ms);
        }
        response.setStatus(code);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", "/status/" + code);
        result.put("httpStatus", code);
        result.put("sleepMs", ms);
        result.put("slowRuleHint", "slow_rules operation:/status/*=8000");
        result.put("ignoreRuleHint", "error_ignore_rules operation:/status/*=503,500,400");
        return result;
    }

    /** 白名单:operation:GET:/.well-known/**=404(Ant) */
    @GetMapping("/.well-known/**")
    public String wellKnown(HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        return "well-known-not-found";
    }

    /** 错误:未捕获异常(span.isError) */
    @GetMapping("/api/trace-alert-demo/error")
    public String error() {
        throw new RuntimeException("trace-alert-demo simulated error");
    }

    /** 错误:HTTP 500 */
    @GetMapping("/api/trace-alert-demo/http500")
    public String http500(HttpServletResponse response) {
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return "internal error";
    }

    /** 白名单:operation:GET:/api/exists/*=404(Ant) */
    @GetMapping("/api/exists/{id}")
    public String exists(@PathVariable String id, HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        return "not-found:" + id;
    }

    /** 白名单:operation:GET:/inner/business-test/**=404,410(Ant) */
    @RequestMapping("/inner/business-test/probe")
    public String businessProbe(HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        return "probe-404";
    }

    /** 正常快速请求(对照组) */
    @GetMapping("/api/trace-alert-demo/ok")
    public String ok() {
        return "ok";
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
