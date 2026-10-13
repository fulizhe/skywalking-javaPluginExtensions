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

package org.apache.skywalking.apm.plugin.dynamicdebugruntime.v9;

import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;

import cn.hutool.core.exceptions.UtilException;
import cn.hutool.core.util.ReflectUtil;

/**
 * 各插件"更详尽链路上下文"采集开关的统一实现。
 * <p>
 * {@link DynamicDebugRuntimeEnableInterceptor} 与 {@link DynamicDebugRuntimeDisableInterceptor}
 * 都委托到这里, 避免开关逻辑重复。
 * <p>
 * 某个插件未装配(其 config 类不存在)或字段名不匹配时, 只告警并跳过,
 * 不会中断其余开关的切换。
 */
final class DynamicDebugRuntimeSwitch {

	private static final ILog LOGGER = LogManager.getLogger(DynamicDebugRuntimeSwitch.class);

	static final String CLASS_NAME_FEIGN_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.feign.http.v9.FeignPluginConfig$Plugin$Feign";
	static final String CLASS_NAME_JDBC_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.jdbc.JDBCPluginConfig$Plugin$JDBC";
	static final String CLASS_NAME_SPRINGMVC_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.spring.mvc.commons.SpringMVCPluginConfig$Plugin$SpringMVC";
	static final String CLASS_NAME_HTTPCLIENT_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig$Plugin$HttpClient";
	static final String CLASS_NAME_TOMCAT_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.tomcat78x.TomcatPluginConfig$Plugin$Tomcat";

	private DynamicDebugRuntimeSwitch() {
	}

	/**
	 * 同步改写 feign / jdbc / httpclient / springmvc / tomcat 共 5 个插件 PluginConfig 的静态开关字段。
	 *
	 * @param isEnable true 打开, false 关闭
	 */
	static void setEnabled(final boolean isEnable) {
		LOGGER.info("### toggle debug for [ feign, jdbc, httpclient, springmvc, tomcat ] to [ {} ]", isEnable);
		toggle(CLASS_NAME_FEIGN_PLUGIN_CONFIG, "COLLECT_REQUEST_BODY", isEnable);
		toggle(CLASS_NAME_JDBC_PLUGIN_CONFIG, "TRACE_SQL_PARAMETERS", isEnable);
		toggle(CLASS_NAME_HTTPCLIENT_PLUGIN_CONFIG, "COLLECT_HTTP_PARAMS", isEnable);
		toggle(CLASS_NAME_SPRINGMVC_PLUGIN_CONFIG, "COLLECT_HTTP_PARAMS", isEnable);
		toggle(CLASS_NAME_TOMCAT_PLUGIN_CONFIG, "COLLECT_HTTP_PARAMS", isEnable);
		LOGGER.info("### toggle debug done.");
	}

	/**
	 * 反射改写指定 {@code PluginConfig} 的静态开关字段。
	 * <p>
	 * 类不存在(插件未装配)或字段不可写时只告警, 不抛出, 以保证其余开关照常切换。
	 */
	private static void toggle(final String configClassName, final String fieldName, final boolean isEnable) {
		try {
			final ClassLoader cl = DynamicDebugRuntimeSwitch.class.getClassLoader();
			final Class<?> cls = cl.loadClass(configClassName);
			ReflectUtil.setFieldValue(cls, fieldName, isEnable);
			LOGGER.info("### [ {}#{} ] -> [ {} ]", configClassName, fieldName, isEnable);
		} catch (ClassNotFoundException e) {
			LOGGER.warn("### plugin config [ {} ] not found, skipped. [ {}#{} ] unchanged.", configClassName,
					configClassName, fieldName);
		} catch (SecurityException | UtilException e) {
			LOGGER.warn(e, "### failed to toggle [ {}#{} ], skipped.", configClassName, fieldName);
		}
	}

	/**
	 * 统一的异常上报: 记录日志, 并在存在 trace 上下文时把异常挂到当前 span。
	 */
	static void reportFailure(final Throwable t) {
		LOGGER.error(t, "### toggle debug failed.");
		// 调用点可能不在任何 trace 上下文中; ContextManager.activeSpan() 在无上下文时会抛 NPE,
		// 必须先经 isActive() 判定(参见 ContextManager#activeSpan 的 javadoc)。
		if (ContextManager.isActive()) {
			ContextManager.activeSpan().errorOccurred().log(t);
		}
	}
}
