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

package org.apache.skywalking.apm.toolkit;

import java.util.Collections;
import java.util.Map;

/**
 * 宿主工具类桩(FQCN 契约):插件按类名增强本类静态方法。
 * 无 agent 时,方法体为桩实现(空数据);挂载 agent 后,由插件拦截器接管。
 */
public class SWLogfileReporterUtils {

    /**
     * 运行时开关:开启本地内存报告
     */
    public static void enableReport(Map<String, Object> config) {
    }

    /**
     * 运行时开关:关闭本地内存报告
     */
    public static void disableReport(Map<String, Object> config) {
    }

    /**
     * 统计快照:链路段缓存、JVM、meter、实例属性、应用日志、profile 快照、告警指标
     */
    public static Map<String, Object> statisticStatus() {
        return Collections.emptyMap();
    }

    /**
     * 性能剖析:发起采样
     */
    public static Map<String, Object> startProfile(Map<String, Object> params) {
        return Collections.emptyMap();
    }

    /**
     * 性能剖析:读取采样数据
     */
    public static Map<String, Object> getProfileDatas() {
        return Collections.emptyMap();
    }
}
