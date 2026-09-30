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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openskywalking.demo.controller;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * override-hutool-http-5.x 插件的 hutool HTTP 出口触发端点(插件增强目标:cn.hutool.http.HttpRequest#execute)。
 * <p>
 * 全部走回环(127.0.0.1:WebPort)自调用,不依赖外部网络;每类出口对应插件 README 声明的一种采集面:
 * <ul>
 *   <li>{@code /get-query} —— query string(官方开关 {@code plugin.httpclient.collect_http_params})</li>
 *   <li>{@code /post-form} —— application/x-www-form-urlencoded 表单体(共享 override 开关)</li>
 *   <li>{@code /post-json} —— {@code body(...)} 原始体</li>
 *   <li>{@code /post-multipart} —— multipart 文本字段 + 文件元数据({@code http.request.files})</li>
 *   <li>{@code /error-call} —— 目标 500,验证 exit span 置 isError</li>
 * </ul>
 * 每个触发端点回显 <b>业务自己读到的</b>响应体({@code businessBody}),用于验证"监控只助力不阻碍":
 * 插件采集 {@code http.response.body} 的同时,业务侧仍能完整读到 body。
 * <p>
 * {@code phase} 参数进入回环目标路径(形如 {@code /api/hutool-demo/echo/on}),使断言可按
 * operationName 精确区分阶段的 exit span —— 例如开关关断后验证"span 仍在、参数 tag 已消失"。
 */
@RestController
public class HutoolHttpDemoController {

    /** 回环目标路径:phase 进路径,便于按 operationName 精确过滤出口 span */
    private static final String ECHO_PATH_PREFIX = "/api/hutool-demo/echo/";

    /** multipart 用临时文件名(断言 http.request.files 的 filename) */
    private static final String UPLOAD_FILE_NAME = "hutool-verify-upload.txt";

    private static final String UPLOAD_FILE_CONTENT = "hutool-http-plugin-e2e-verification";

    /** GET 带 query string:验证 query 采集 + 响应体采集 + 业务仍读到 body */
    @GetMapping("/api/hutool-demo/get-query")
    public Object getQuery(@RequestParam(defaultValue = "hutool-query-marker") String marker) {
        String target = selfBase() + ECHO_PATH_PREFIX + "query?marker=" + marker;
        return probe("get-query", target, HttpRequest.get(target), "query=" + marker);
    }

    /** POST 表单体:验证 form 采集(与 override-httpclient 场景的表单断言同构) */
    @GetMapping("/api/hutool-demo/post-form")
    public Object postForm(@RequestParam(defaultValue = "hutool-form-marker") String value,
            @RequestParam(defaultValue = "form") String phase) {
        String target = selfBase() + ECHO_PATH_PREFIX + phase;
        return probe("post-form", target, HttpRequest.post(target).form("value", value), "form=value=" + value);
    }

    /** POST 原始体(JSON):验证 body(...) 文本体采集 */
    @GetMapping("/api/hutool-demo/post-json")
    public Object postJson(@RequestParam(defaultValue = "hutool-json-marker") String value,
            @RequestParam(defaultValue = "json") String phase) {
        String target = selfBase() + ECHO_PATH_PREFIX + phase;
        String jsonBody = "{\"value\":\"" + value + "\"}";
        HttpRequest request = HttpRequest.post(target)
                .header("Content-Type", ContentType.JSON.getValue())
                .body(jsonBody);
        return probe("post-json", target, request, "body=" + jsonBody);
    }

    /** POST multipart(文本字段 + 文件):验证 http.request.files 文件元数据采集 */
    @GetMapping("/api/hutool-demo/post-multipart")
    public Object postMultipart(@RequestParam(defaultValue = "hutool-multipart-marker") String value,
            @RequestParam(defaultValue = "multipart") String phase) {
        String target = selfBase() + ECHO_PATH_PREFIX + phase;
        File upload = newUploadFile();
        try {
            HttpRequest request = HttpRequest.post(target).form("value", value).form("file", upload);
            return probe("post-multipart", target, request,
                    "参数 tag 应含 value=" + value + ";文件 tag 应含 filename=" + UPLOAD_FILE_NAME);
        } finally {
            FileUtil.del(upload);
        }
    }

    /** 目标返回 500:验证 exit span 置 isError(HTTP 状态码 >= 400) */
    @GetMapping("/api/hutool-demo/error-call")
    public Object errorCall() {
        String target = selfBase() + "/api/hutool-demo/status/500";
        return probe("error-call", target, HttpRequest.get(target), "目标 500 -> exit span isError=true");
    }

    /** 回环目标:POST —— 原样回显请求体(供业务侧核对插件采到的 body 与业务读到的 body 是否一致) */
    @PostMapping(value = ECHO_PATH_PREFIX + "{phase}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> echoPost(@PathVariable String phase, HttpServletRequest request) throws IOException {
        return echo(phase, request);
    }

    /** 回环目标:GET —— 无请求体 */
    @GetMapping(value = ECHO_PATH_PREFIX + "{phase}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> echoGet(@PathVariable String phase) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("echo", "ok");
        result.put("phase", phase);
        result.put("method", "GET");
        return result;
    }

    /** 回环目标:指定状态码(错误面验证) */
    @GetMapping("/api/hutool-demo/status/{code}")
    public Map<String, Object> status(@PathVariable int code, HttpServletResponse response) {
        response.setStatus(code);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("echo", "status-" + code);
        result.put("code", code);
        return result;
    }

    private Map<String, Object> echo(String phase, HttpServletRequest request) throws IOException {
        String body = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("echo", body);
        result.put("phase", phase);
        result.put("method", request.getMethod());
        result.put("contentType", request.getContentType());
        result.put("bodyLength", body.length());
        return result;
    }

    /**
     * 执行一次 hutool 出口调用并回报:业务自读响应体 + 期望被采集到的 tag 片段。
     * 业务读 body 放在插件采集之后(拦截器在 execute() 返回后采 http.response.body),
     * 用于验证业务侧未因插件采集而读不到 body。
     */
    private Map<String, Object> probe(String api, String target, HttpRequest request, String expectedTagHint) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("client", "hutool-http");
        result.put("api", api);
        result.put("targetUrl", target);
        result.put("expectedTagHint", expectedTagHint);
        HttpResponse response = null;
        try {
            response = request.execute();
            result.put("httpStatus", response.getStatus());
            String businessBody = readBody(response);
            result.put("businessBody", businessBody);
            result.put("businessBodyLength", lengthOf(businessBody));
        } catch (Exception e) {
            result.put("error", e.getClass().getName() + ": " + e.getMessage());
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Exception ignored) {
                    // 演示端点:关闭失败不影响断言
                }
            }
        }
        result.put("verificationHint", "exit span operationName=" + pathOf(target) + ",期望 tag:" + expectedTagHint);
        return result;
    }

    private String readBody(HttpResponse response) {
        try {
            return response.body();
        } catch (Exception e) {
            return "<business-read-failed:" + e.getClass().getSimpleName() + ">";
        }
    }

    private int lengthOf(String body) {
        return body == null ? 0 : body.length();
    }

    private static String pathOf(String url) {
        int schemeEnd = url.indexOf("://");
        int pathStart = schemeEnd < 0 ? 0 : url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? "/" : url.substring(pathStart);
    }

    private static String selfBase() {
        return "http://127.0.0.1:" + System.getenv().getOrDefault("WebPort", "9600");
    }

    private static File newUploadFile() {
        File upload = new File(System.getProperty("java.io.tmpdir"), UPLOAD_FILE_NAME);
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(upload, StandardCharsets.UTF_8.name());
            writer.print(UPLOAD_FILE_CONTENT);
        } catch (IOException e) {
            throw new IllegalStateException("create upload file failed: " + upload, e);
        } finally {
            IoUtil.close(writer);
        }
        return upload;
    }
}
