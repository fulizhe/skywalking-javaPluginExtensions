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

import org.apache.skywalking.apm.toolkit.trace.SWDebugUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * dynamic-debug-runtime-9.x-plugin 的运行时调试开关演示读口:
 * 打开/关闭 feign、jdbc、httpclient、springmvc、tomcat 五类"更详尽链路上下文"的采集。
 * <p>
 * 未挂载该插件时,桩方法为空操作,端点照常响应(便于挂载前后对比)。
 */
@RestController
public class SWDebugController {

	/** 打开调试采集:GET /sw/debug/enable */
	@GetMapping("/sw/debug/enable")
	public String enable() {
		SWDebugUtils.enableDebug();
		return "debug=enabled";
	}

	/** 关闭调试采集:GET /sw/debug/disable */
	@GetMapping("/sw/debug/disable")
	public String disable() {
		SWDebugUtils.disableDebug();
		return "debug=disabled";
	}
}
