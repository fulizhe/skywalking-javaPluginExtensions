/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openskywalking.demo.controller;

import java.util.Map;

import org.openskywalking.demo.service.DepsDemoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 依赖拓扑演示的造数端点（HTTP 入口）：给 Cache / Database / MQ 三层与真实外呼各留一个入口，
 * 让依赖拓扑页({@code /dashboards/topology.html})在演示环境里就有分层可看。
 *
 * <p><b>本类只是 HTTP 薄壳</b>，实现全在 {@link DepsDemoService}。这不是为了分层好看，而是
 * {@code /fullSample} 也要用同一套四层调用：如果逻辑留在本类里，全貌入口就得
 * <b>直接调用别的 Controller 的 handler 方法</b>，而 agent 的 Spring MVC 增强会把那些
 * handler 的 operationName 当成本次请求的 Entry —— 实测导致一次 {@code /fullSample} 的
 * 7 个出口全被记到 {@code GET:/api/deps-demo/redis} 名下（依赖拓扑图上出现"redis 挂着
 * HTTP 自调/Kafka/外呼"）。搬进普通 {@code @Service} 后，Entry 只来自真实 HTTP 入口。
 * 详见 {@code docs/notes/2026-10-01-deps-demo-topology-probe.md}。
 *
 * <p>三条读图口径（页面 caveats 同款）：连接没建起来的依赖**不产生边**（Redis/MySQL 连不上时
 * 图上该组件缺席，缺席 ≠ 没有调用）；有边也**未必是红的**（Kafka send 超时那条例外）；
 * 每条出网调用都**显式设超时**（造数端点会被 16 线程压测打、也被页面 5s 轮询读）。
 */
@RestController
@RequestMapping("/api/deps-demo")
public class DepsDemoController {

    @Autowired
    private DepsDemoService deps;

    /** Redis 造数:{@code op=get|set|del}。三种 op 在明细档会落成三个节点。 */
    @GetMapping("/redis")
    public Object redis(@RequestParam(value = "op", defaultValue = "get") String op) {
        return deps.redis(op);
    }

    /** MySQL 造数:{@code sleepMs>0} 走 {@code SLEEP(?)} 造慢边。不建表、不依赖 schema。 */
    @GetMapping("/mysql")
    public Object mysql(@RequestParam(value = "sleepMs", defaultValue = "0") int sleepMs) {
        return deps.mysql(sleepMs);
    }

    /** Kafka 造数:{@code op=produce|consume}。 */
    @GetMapping("/kafka")
    public Object kafka(@RequestParam(value = "op", defaultValue = "produce") String op) {
        return deps.kafka(op);
    }

    /** 真实站点外呼:{@code site=httpbin|baidu|google}（白名单，不接受任意 URL）。 */
    @GetMapping("/http")
    public Object http(@RequestParam("site") String site) {
        return deps.http(site);
    }

    /** 一次打穿四层(依赖面演示与截图的单一入口)。四层耗时叠加但每层有超时,不会挂住。 */
    @GetMapping("/all")
    public Object all(@RequestParam(value = "sleepMs", defaultValue = "0") int sleepMs,
            @RequestParam(value = "site", defaultValue = "httpbin") String site) {
        return deps.all(sleepMs, site);
    }
}