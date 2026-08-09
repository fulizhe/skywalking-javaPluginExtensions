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

import java.util.HashMap;
import java.util.Map;

import org.apache.skywalking.apm.toolkit.SWLogfileReporterUtils;

/**
 * 统计快照读口统一入口。
 * <p>
 * 挂载 agent + logfile-reporter-plugin 时,{@link SWLogfileReporterUtils#statisticStatus()} 被插件增强,
 * 返回五类数据流缓存;未挂载时方法体为桩实现(空 map)。
 * 桩实现与空数据区分:插件增强版恒含顶层键(data/jvm/meterData/logData/instanceProperties/...),
 * 空 map 即"未挂载插件",读口返回明确提示而不是异常。
 */
final class StatisticStatus {

    static final String PLUGIN_KEY = "plugin";
    static final String PLUGIN_ABSENT = "absent";

    private StatisticStatus() {
    }

    /** 是否为"无插件提示"map(恒含 {@value #PLUGIN_KEY} 键) */
    static boolean isHint(Map<String, Object> status) {
        return status != null && status.containsKey(PLUGIN_KEY);
    }

    static Map<String, Object> get() {
        Map<String, Object> status = SWLogfileReporterUtils.statisticStatus();
        if (status == null || status.isEmpty()) {
            Map<String, Object> hint = new HashMap<>(4);
            hint.put("plugin", PLUGIN_ABSENT);
            hint.put("hint", "未挂载 logfile-reporter-plugin:统计快照读口仅返回提示。请以 agent + 插件方式启动(scripts/run-with-agent.ps1)。");
            return hint;
        }
        return status;
    }

    /**
     * 读某一数据流;插件未挂载或该键缺省时返回提示 map。
     */
    static Object value(String key) {
        Map<String, Object> status = get();
        Object value = status.get(key);
        if (value == null) {
            Map<String, Object> hint = new HashMap<>(4);
            hint.put("plugin", PLUGIN_ABSENT);
            hint.put("hint", "统计快照中缺少数据流 " + key + ":插件未挂载或该数据流为空。");
            return hint;
        }
        return value;
    }
}
