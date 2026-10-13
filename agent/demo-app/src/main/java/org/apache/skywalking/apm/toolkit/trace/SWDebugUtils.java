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

package org.apache.skywalking.apm.toolkit.trace;

/**
 * 宿主工具类桩(FQCN 契约):由 dynamic-debug-runtime-9.x-plugin 按类名增强本类静态方法。
 * 无插件时,方法体为桩实现(空操作);挂载该插件后由拦截器接管,反射改写各官方插件的采集开关。
 * <p>
 * 本副本与插件模块 {@code doc/SWDebugUtils.java} 的桩一致(插件 README 约定:业务应用需自带此桩以直接调用)。
 */
public class SWDebugUtils {
	/** 打开调试采集开关(feign / jdbc / httpclient / springmvc / tomcat)。 */
	public static void enableDebug() {
	}

	/** 关闭调试采集开关。 */
	public static void disableDebug() {
	}
}
