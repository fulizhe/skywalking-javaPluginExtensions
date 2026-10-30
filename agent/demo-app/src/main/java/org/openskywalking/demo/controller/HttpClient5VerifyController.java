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

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经 Apache HttpClient 5.x 向应用自身 POST 表单体:供 override-httpclient-5.x 插件采集 body
 * (span tag http.request.params)的端到端验证。回环目标复用
 * {@code TraceAlertVerifyController#echo}。
 */
@RestController
public class HttpClient5VerifyController {

    private final CloseableHttpClient skyWalkingDemoHttpClient5;

    public HttpClient5VerifyController(CloseableHttpClient skyWalkingDemoHttpClient5) {
        this.skyWalkingDemoHttpClient5 = skyWalkingDemoHttpClient5;
    }

    @GetMapping("/api/trace-alert-demo/httpclient5-post")
    public Map<String, Object> httpClient5Post(@RequestParam(defaultValue = "verify-body") String value)
            throws IOException, ParseException {
        String targetUrl = "http://127.0.0.1:" + System.getenv().getOrDefault("WebPort", "9600")
                + "/api/trace-alert-demo/echo";
        HttpPost post = new HttpPost(targetUrl);
        List<NameValuePair> form = new ArrayList<>();
        form.add(new BasicNameValuePair("value", value));
        post.setEntity(new UrlEncodedFormEntity(form, StandardCharsets.UTF_8));
        int httpStatus;
        String bodySnippet;
        try (CloseableHttpResponse response = skyWalkingDemoHttpClient5.execute(post)) {
            httpStatus = response.getCode();
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                bodySnippet = "";
            } else {
                String body = EntityUtils.toString(entity, StandardCharsets.UTF_8);
                bodySnippet = body.length() > 200 ? body.substring(0, 200) + "..." : body;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("client", "apache-httpclient5");
        result.put("traceId", TraceContext.traceId());
        result.put("segmentId", TraceContext.segmentId());
        result.put("targetUrl", targetUrl);
        result.put("httpStatus", httpStatus);
        result.put("bodySnippet", bodySnippet);
        result.put("verificationHint",
                "override-httpclient-5.x 插件应把表单体采为出口 span tag http.request.params=form=value=<value>");
        return result;
    }
}
