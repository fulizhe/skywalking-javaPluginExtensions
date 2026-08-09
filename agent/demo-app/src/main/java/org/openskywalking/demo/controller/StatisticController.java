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

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.skywalking.apm.toolkit.SWLogfileReporterUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/**
 * 统计快照读口(移植自旧项目 sb-skywalking 的 SWLogfileReporterController):
 * 五类数据流(链路段/JVM 指标/meter 指标/应用日志/心跳实例信息)查询端点、组件名解析、运行时开关。
 * <p>
 * 无插件时所有读口返回明确提示(map 含 {@code plugin: "absent"}),不抛异常。
 */
@RestController
public class StatisticController {

    /** 静态缓存 componentId -> 组件名 映射表(component-libraries.yml) */
    private static final Map<Integer, String> COMPONENT_ID_NAME_MAP = new HashMap<>();
    private static final AtomicBoolean COMPONENT_MAP_LOADED = new AtomicBoolean(false);

    /** 链路段读口:/statistic 返回 data(按 endTime 倒序、附可读时间与组件名),去掉 jvm/instanceProperties 两流 */
    @GetMapping("/statistic")
    public Object statistic() {
        Map<String, Object> status = StatisticStatus.get();
        if (StatisticStatus.isHint(status)) {
            return status;
        }
        status.remove("jvm");
        status.remove("instanceProperties");

        loadComponentMapIfNeeded();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        Object dataObj = status.get("data");
        if (dataObj instanceof Map) {
            for (Object traceId : ((Map<?, ?>) dataObj).keySet()) {
                Object logsObj = ((Map<?, ?>) ((Map<?, ?>) dataObj).get(traceId)).get("logs");
                if (logsObj instanceof Iterable) {
                    for (Object log : (Iterable<?>) logsObj) {
                        if (log instanceof Map) {
                            Object spansObj = ((Map<?, ?>) log).get("spans");
                            if (spansObj instanceof Iterable) {
                                enrichSpans((Map<String, Object>) log, (Iterable<?>) spansObj, sdf);
                            }
                        }
                    }
                }
            }
        }
        return status;
    }

    /** JVM 指标读口 */
    @GetMapping("/statisticJVM")
    public Object statisticJVM() {
        return StatisticStatus.value("jvm");
    }

    /** meter 指标读口 */
    @GetMapping("/statisticMeter")
    public Object statisticMeter() {
        return StatisticStatus.value("meterData");
    }

    /** 应用日志读口 */
    @GetMapping("/statisticLogs")
    public Object statisticLogs() {
        return StatisticStatus.value("logData");
    }

    /** 心跳实例信息读口 */
    @GetMapping("/statisticInstanceProperties")
    public Object statisticInstanceProperties() {
        return StatisticStatus.value("instanceProperties");
    }

    /** 运行时开关:POST /toggle?enable=true|false,关闭后链路段统计快照停止增长,开启后恢复 */
    @PostMapping("/toggle")
    public Object toggle(@RequestParam("enable") Boolean enable) {
        if (Boolean.TRUE.equals(enable)) {
            SWLogfileReporterUtils.enableReport(null);
        } else {
            SWLogfileReporterUtils.disableReport(null);
        }
        return true;
    }

    private void enrichSpans(Map<String, Object> log, Iterable<?> spans, SimpleDateFormat sdf) {
        List<Object> spanList = new java.util.ArrayList<>();
        for (Object span : spans) {
            spanList.add(span);
        }
        Collections.sort(spanList, SPAN_TIME_DESC);
        log.put("spans", spanList);

        for (Object span : spanList) {
            if (!(span instanceof Map)) {
                continue;
            }
            Map<?, ?> spanMap = (Map<?, ?>) span;
            Object startTimeObj = spanMap.get("startTime");
            Object endTimeObj = spanMap.get("endTime");
            if (startTimeObj instanceof Number) {
                ((Map<String, Object>) spanMap).put("startTimeReadable", sdf.format(new Date(((Number) startTimeObj).longValue())));
            }
            if (endTimeObj instanceof Number) {
                ((Map<String, Object>) spanMap).put("endTimeReadable", sdf.format(new Date(((Number) endTimeObj).longValue())));
            }
            Object componentIdObj = spanMap.get("componentId");
            if (componentIdObj instanceof Number) {
                String componentName = COMPONENT_ID_NAME_MAP.get(((Number) componentIdObj).intValue());
                if (componentName != null) {
                    ((Map<String, Object>) spanMap).put("componentName", componentName);
                }
            }
        }
    }

    /** span 按 endTime(缺省用 startTime)倒序 */
    private static final Comparator<Object> SPAN_TIME_DESC = (o1, o2) -> {
        long t1 = spanTime(o1);
        long t2 = spanTime(o2);
        return Long.compare(t2, t1);
    };

    private static long spanTime(Object o) {
        if (o instanceof Map) {
            Object endTime = ((Map<?, ?>) o).get("endTime");
            if (endTime instanceof Number) {
                return ((Number) endTime).longValue();
            }
            Object startTime = ((Map<?, ?>) o).get("startTime");
            if (startTime instanceof Number) {
                return ((Number) startTime).longValue();
            }
        }
        return 0L;
    }

    private void loadComponentMapIfNeeded() {
        if (COMPONENT_MAP_LOADED.get()) {
            return;
        }
        synchronized (COMPONENT_ID_NAME_MAP) {
            if (COMPONENT_MAP_LOADED.get()) {
                return;
            }
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("component-libraries.yml")) {
                if (in != null) {
                    Object obj = new Yaml().load(in);
                    if (obj instanceof Map) {
                        for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                            Object value = entry.getValue();
                            if (value instanceof Map) {
                                Object idObj = ((Map<?, ?>) value).get("id");
                                if (idObj instanceof Number) {
                                    COMPONENT_ID_NAME_MAP.put(((Number) idObj).intValue(), entry.getKey().toString());
                                }
                            }
                        }
                    }
                }
                COMPONENT_MAP_LOADED.set(true);
            } catch (Exception e) {
                // 组件映射表读取失败不影响读口主流程
            }
        }
    }
}
