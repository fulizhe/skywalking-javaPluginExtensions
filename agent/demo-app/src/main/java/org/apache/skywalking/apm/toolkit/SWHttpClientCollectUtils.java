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
 * 宿主工具类桩(FQCN 契约):由 override-httpclient-4.x-plugin 按类名增强本类静态方法。
 * 无插件时,方法体为桩实现(返回未启用/空数据);挂载 override-httpclient-4.x 插件后由拦截器接管。
 * <p>
 * 本副本与插件模块源码中的同名桩一致(插件 README 约定:业务应用需自带此桩以直接调用)。
 */
public class SWHttpClientCollectUtils {
    public static Object enableCollect(Map<String, Object> config) {
        return Boolean.FALSE;
    }

    public static Object disableCollect(Map<String, Object> config) {
        return Boolean.FALSE;
    }

    public static Map<String, Object> statisticStatus() {
        return Collections.emptyMap();
    }
}
