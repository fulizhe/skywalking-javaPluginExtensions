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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.skywalking.apm.toolkit.SWHttpClientCollectUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * override-httpclient-4.x 插件的 httpclient 参数采集(collect)演示读口
 * (移植自旧项目 sb-skywalking 的 SWHttpClientCollectController):
 * 运行时开关与采集快照查看端点。未挂载 override-httpclient-4.x 插件时,桩方法返回
 * 未启用/空数据,读口照常响应(便于对比)。
 */
@RestController
public class SWHttpClientCollectController {

    private static final AtomicReference<Boolean> LAST_TOGGLED_STATUS = new AtomicReference<>(Boolean.FALSE);

    /** 采集状态读口:返回 enabled / dataCount / keys 摘要 */
    @GetMapping("/httpclient/collect/status")
    public Object status() {
        return buildSummary(safeStatisticStatus());
    }

    /** 采集快照读口:在摘要基础上附带原始 statisticStatus */
    @GetMapping("/httpclient/collect/statistic")
    public Object statistic() {
        Map<String, Object> statisticStatus = safeStatisticStatus();
        Map<String, Object> response = buildSummary(statisticStatus);
        response.put("raw", statisticStatus);
        return response;
    }

    /** 运行时开关:POST /httpclient/collect/toggle?enable=true|false,返回值回读实际生效状态 */
    @PostMapping("/httpclient/collect/toggle")
    public Object toggle(Boolean enable) {
        Boolean targetEnabled = Boolean.TRUE.equals(enable);
        Object invokeResult;
        if (Boolean.TRUE.equals(enable)) {
            invokeResult = SWHttpClientCollectUtils.enableCollect(Collections.emptyMap());
        } else {
            invokeResult = SWHttpClientCollectUtils.disableCollect(Collections.emptyMap());
        }

        Boolean resultEnabled = toBoolean(invokeResult);
        LAST_TOGGLED_STATUS.set(resultEnabled != null ? resultEnabled : targetEnabled);

        Map<String, Object> response = buildSummary(safeStatisticStatus());
        response.put("invokeResult", invokeResult);
        return response;
    }

    private Map<String, Object> safeStatisticStatus() {
        Map<String, Object> statisticStatus = SWHttpClientCollectUtils.statisticStatus();
        return statisticStatus == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(statisticStatus);
    }

    private Map<String, Object> buildSummary(Map<String, Object> statisticStatus) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", extractEnabled(statisticStatus));
        response.put("dataCount", extractDataCount(statisticStatus));
        response.put("keys", statisticStatus.keySet());
        return response;
    }

    private boolean extractEnabled(Map<String, Object> source) {
        Boolean parsed = toBoolean(source.get("effectiveCollectHttpParams"));
        if (parsed != null) {
            LAST_TOGGLED_STATUS.set(parsed);
            return parsed.booleanValue();
        }
        return LAST_TOGGLED_STATUS.get();
    }

    private int extractDataCount(Map<String, Object> source) {
        Object candidate = firstNonNull(source, "data", "logs", "items", "records", "payloads");
        if (candidate instanceof Map) {
            return ((Map<?, ?>) candidate).size();
        }
        if (candidate instanceof Collection) {
            return ((Collection<?>) candidate).size();
        }
        if (candidate instanceof Object[]) {
            return ((Object[]) candidate).length;
        }
        if (candidate instanceof Number) {
            return ((Number) candidate).intValue();
        }
        return 0;
    }

    private Object firstNonNull(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) {
                return source.get(key);
            }
        }
        return null;
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            String normalized = ((String) value).trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized) || "on".equals(normalized) || "enabled".equals(normalized)
                    || "enable".equals(normalized) || "1".equals(normalized)) {
                return Boolean.TRUE;
            }
            if ("false".equals(normalized) || "off".equals(normalized) || "disabled".equals(normalized)
                    || "disable".equals(normalized) || "0".equals(normalized)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }
}
