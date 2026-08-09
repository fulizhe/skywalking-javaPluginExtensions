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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openskywalking.demo.tracealert.TraceAlertWebhookStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收 logfile-reporter Agent 的 HTTP Webhook(路径需与 agent 配置中 webhook 契约一致):
 * 默认契约 {@code http://127.0.0.1:${WebPort:9600}/inner/sw/trace-alert},即演示应用自身 9600。
 * 移植自旧项目 sb-skywalking 的 TraceAlertWebhookController。
 */
@RestController
public class TraceAlertWebhookController {

    private static final DateTimeFormatter RECEIVED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Agent 慢/错链路告警回调 */
    @PostMapping("/inner/sw/trace-alert")
    public Map<String, Object> onTraceAlert(@RequestBody Map<String, Object> payload) {
        Map<String, Object> stored = new LinkedHashMap<>(payload);
        stored.put("_receivedAt", LocalDateTime.now().format(RECEIVED_AT));
        TraceAlertWebhookStore.record(stored);
        return Collections.singletonMap("received", TraceAlertWebhookStore.size());
    }

    /** 查看最近收到的 Webhook 告警 */
    @GetMapping("/inner/sw/trace-alert/recent")
    public Map<String, Object> recent() {
        List<Map<String, Object>> events = TraceAlertWebhookStore.recent();
        Map<String, Object> result = new LinkedHashMap<>(4);
        result.put("count", events.size());
        result.put("events", events);
        return result;
    }

    /** 清空 Webhook 接收缓存 */
    @PostMapping("/inner/sw/trace-alert/clear")
    public Map<String, Object> clear() {
        TraceAlertWebhookStore.clear();
        return Collections.singletonMap("ok", true);
    }
}
