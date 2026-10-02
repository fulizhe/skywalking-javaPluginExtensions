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
import org.apache.skywalking.apm.toolkit.SWMetricsUtils;
import org.apache.skywalking.apm.toolkit.SWSelfStatUtils;
import org.apache.skywalking.apm.toolkit.SWTraceParityUtils;
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

    /** 影子对账读口:/inner/sw/trace-parity 返回 H2 影子对账计数与 H2 存储状态 */
    @GetMapping("/inner/sw/trace-parity")
    public Map<String, Object> traceParity() {
        return SWTraceParityUtils.statisticParity();
    }

    /**
     * 监控自身的运行指标:/inner/sw/self-stat 返回「采集入口丢弃数 / 消费线程单批耗时 /
     * 未提交 segment 积压 / H2 行数与内存估算 / 环形载荷文件写指针与压缩率 / JVM 堆」。
     * <p>
     * 与上面几个读口的关系：那些回答<b>被监控的业务发生了什么</b>，这一个回答
     * <b>监控系统自己是否轻量、健康、没拖垮主业务</b>——落进页面 {@code /dashboards/self-stat.html}。
     * </p>
     */
    @GetMapping("/inner/sw/self-stat")
    public Map<String, Object> selfStat() {
        return SWSelfStatUtils.statisticSelf();
    }

    /** 按 traceId 从 H2/环形文件取回整条链路(与旧 data[traceId].logs 同契约),供人工查看 */
    @GetMapping("/inner/sw/trace-query")
    public Object traceQuery(@RequestParam("traceId") String traceId) {
        return enrichTraceView(SWTraceParityUtils.queryTrace(traceId));
    }

    /** 从内存热层(KeyedLocalStore)按 traceId 取回整条链路,供与 H2 视图双源对照 */
    @GetMapping("/inner/sw/trace-memory")
    public Object traceMemory(@RequestParam("traceId") String traceId) {
        return enrichTraceView(SWTraceParityUtils.getTraceViewFromMemory(traceId));
    }

    /** 最近 N 条 segment header(供挑选 traceId) */
    @GetMapping("/inner/sw/trace-recent")
    public Object traceRecent(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        return SWTraceParityUtils.recentTraces(limit);
    }

    /** 慢查询:按 endpoint + 耗时阈值返回慢段键值对行集合(供慢查询页表格与下钻) */
    @GetMapping("/inner/sw/trace-slow")
    public Object traceSlow(@RequestParam("endpoint") String endpoint,
            @RequestParam(value = "minLatencyMs", defaultValue = "0") int minLatencyMs,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return SWTraceParityUtils.querySlowTraces(endpoint, minLatencyMs, limit);
    }

    /** 统一为链路视图补可读时间与组件名(spans 按 endTime 倒序);H2 与内存两侧共用 */
    private Object enrichTraceView(Object result) {
        if (result instanceof Map) {
            loadComponentMapIfNeeded();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            Object logsObj = ((Map<?, ?>) result).get("logs");
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
        return result;
    }

    /** Trace 指标实时快照:/inner/sw/metrics 返回内存窗口分钟桶与运行计数(供大屏 KPI/当前窗口) */
    @GetMapping("/inner/sw/metrics")
    public Map<String, Object> metrics() {
        return SWMetricsUtils.statisticMetrics();
    }

    /** Trace 指标条件查询:/inner/sw/metrics/query 按 endpoint/桶范围/分辨率返回历史指标行(供大屏趋势与 endpoint 表) */
    @GetMapping("/inner/sw/metrics/query")
    public Map<String, Object> metricsQuery(
            @RequestParam(value = "endpoint", required = false) String endpoint,
            @RequestParam(value = "fromBucket", required = false) Long fromBucket,
            @RequestParam(value = "toBucket", required = false) Long toBucket,
            @RequestParam(value = "resolution", required = false) String resolution,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "aggregate", required = false) Boolean aggregate) {
        Map<String, Object> condition = new HashMap<>(8);
        if (endpoint != null) {
            condition.put("endpoint", endpoint);
        }
        if (fromBucket != null) {
            condition.put("fromBucket", fromBucket);
        }
        if (toBucket != null) {
            condition.put("toBucket", toBucket);
        }
        if (resolution != null) {
            condition.put("resolution", resolution);
        }
        if (limit != null) {
            condition.put("limit", limit);
        }
        if (aggregate != null) {
            condition.put("aggregate", aggregate);
        }
        return SWMetricsUtils.queryMetrics(condition);
    }

    /** 端点极端值 trace 读口:/inner/sw/metrics/extremes 返回每端点最大耗时那一次的 traceId(指标→链路追溯入口) */
    @GetMapping("/inner/sw/metrics/extremes")
    public Map<String, Object> metricsExtremes() {
        return SWMetricsUtils.extremeTraces();
    }

    /**
     * 依赖拓扑读口:/inner/sw/topology 返回「入口端点 × 外部依赖」的边列表。
     * <p>
     * 插件侧只透出 {@code componentId} 整数，<b>组件名在宿主侧翻译</b>（复用既有组件库映射，
     * Agent 侧因此不必打包组件库）。翻译不到时按 {@code spanLayer} 归类退化——
     * 实测 hutool-http 的 componentId=128 就不在组件库里（见 docs/notes/2026-09-30-exit-span-runtime-probe.md），
     * 没有 fallback 的话该依赖节点会是空的。
     * </p>
     * 参数:{@code view}=summary(只回组件类型,给总览图)|detail(带出口操作名,给明细图)、
     * {@code limit}(默认 2000)。
     */
    @GetMapping("/inner/sw/topology")
    public Map<String, Object> topology(
            @RequestParam(value = "view", required = false, defaultValue = "detail") String view,
            @RequestParam(value = "limit", required = false) Integer limit) {
        Map<String, Object> condition = new HashMap<>(4);
        condition.put("view", view);
        if (limit != null) {
            condition.put("limit", limit);
        }
        Map<String, Object> result = SWMetricsUtils.dependencyTopology(condition);
        loadComponentMapIfNeeded();
        Object edgesObj = result.get("edges");
        if (edgesObj instanceof List) {
            List<?> edges = (List<?>) edgesObj;
            for (Object edgeObj : edges) {
                if (!(edgeObj instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> edge = (Map<String, Object>) edgeObj;
                Object cidObj = edge.get("componentId");
                if (cidObj instanceof Number) {
                    edge.put("componentName", componentDisplayName(((Number) cidObj).intValue(),
                            String.valueOf(edge.get("spanLayer"))));
                }
            }
        }
        // 自启动以来的依赖清单：同样只透出 componentId，组件名在这里补（与上面同一套三级 fallback）
        Object depsObj = result.get("dependencies");
        if (depsObj instanceof List) {
            List<?> dependencies = (List<?>) depsObj;
            for (Object depObj : dependencies) {
                if (!(depObj instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> dep = (Map<String, Object>) depObj;
                Object cidObj = dep.get("componentId");
                if (cidObj instanceof Number) {
                    dep.put("componentName", componentDisplayName(((Number) cidObj).intValue(),
                            String.valueOf(dep.get("spanLayer"))));
                }
            }
        }
        return result;
    }

    /**
     * 组件显示名:先查组件库,查不到则按 spanLayer 归类,再查不到才退化成 {@code component-<id>}。
     * 三级 fallback 的原因见 {@code /inner/sw/topology} 的注释——组件库并不覆盖全部插件注册的组件。
     */
    private String componentDisplayName(int componentId, String spanLayer) {
        String name = COMPONENT_ID_NAME_MAP.get(componentId);
        if (name != null && !name.isEmpty()) {
            return name;
        }
        if (spanLayer != null && !spanLayer.isEmpty() && !"null".equals(spanLayer)) {
            return spanLayer + "(#" + componentId + ")";
        }
        return "component-" + componentId;
    }

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

    /** trace 告警运行指标读口(agent 侧 traceAlert 节点);插件未挂载时返回提示,插件挂载但告警未启用时返回 enabled=false */
    @GetMapping("/statisticTraceAlert")
    public Object statisticTraceAlert() {
        Map<String, Object> status = StatisticStatus.get();
        if (StatisticStatus.isHint(status)) {
            return status;
        }
        Object traceAlert = status.get("traceAlert");
        if (traceAlert != null) {
            return traceAlert;
        }
        Map<String, Object> fallback = new HashMap<>(4);
        fallback.put("enabled", false);
        fallback.put("hint", "未检测到 traceAlert 节点:请确认已挂载含 Alert 的 logfile-reporter-plugin,且 plugin.logfilereporter.alert.enabled=true");
        return fallback;
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
