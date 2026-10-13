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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 * 覆盖: 入站 HTTP（SpringMVC 参数 + HTTP 头、Tomcat 7/8 与 10.x）、出站 HTTP（HttpClient、Feign）、
 * RPC（Dubbo 2.7 / 3.x 消费者与提供者入参）、SQL（JDBC 系）。
 * <p>
 * 某个插件未装配(其 config 类不存在)或字段名不匹配时, 只告警并跳过, 不会中断其余开关的切换。
 */
final class DynamicDebugRuntimeSwitch {

	private static final ILog LOGGER = LogManager.getLogger(DynamicDebugRuntimeSwitch.class);

	static final String CLASS_NAME_FEIGN_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.feign.http.v9.FeignPluginConfig$Plugin$Feign";
	static final String CLASS_NAME_JDBC_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.jdbc.JDBCPluginConfig$Plugin$JDBC";
	static final String CLASS_NAME_SPRINGMVC_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.spring.mvc.commons.SpringMVCPluginConfig$Plugin$SpringMVC";
	static final String CLASS_NAME_HTTPCLIENT_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig$Plugin$HttpClient";
	static final String CLASS_NAME_TOMCAT_7X_8X_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.tomcat78x.TomcatPluginConfig$Plugin$Tomcat";
	static final String CLASS_NAME_TOMCAT_10X_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.tomcat10x.TomcatPluginConfig$Plugin$Tomcat";
	static final String CLASS_NAME_DUBBO_2X_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.asf.dubbo.DubboPluginConfig$Plugin$Dubbo";
	static final String CLASS_NAME_DUBBO_3X_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.asf.dubbo3.DubboPluginConfig$Plugin$Dubbo";
	/** SpringMVC 的 HTTP 头采集名单所在类(与 COLLECT_HTTP_PARAMS 分开的开关, 字段是 List 而非 boolean)。 */
	static final String CLASS_NAME_SPRINGMVC_HTTP_CONFIG = "org.apache.skywalking.apm.plugin.spring.mvc.commons.SpringMVCPluginConfig$Plugin$Http";

	private static final String FIELD_COLLECT_HTTP_PARAMS = "COLLECT_HTTP_PARAMS";
	private static final String FIELD_INCLUDE_HTTP_HEADERS = "INCLUDE_HTTP_HEADERS";

	/**
	 * agent 配置({@code plugin.springmvc.include_http_headers})解析出的 header 名单。
	 * enable 时恢复到它, disable 时清空 —— 故必须在**任何写入之前**抓取一次, 否则 disable 清空后名单永久丢失。
	 */
	private static volatile boolean headerNamesCaptured = false;
	private static volatile List<String> headerNamesWhenEnabled = Collections.emptyList();

	private DynamicDebugRuntimeSwitch() {
	}

	/**
	 * 同步改写各插件 PluginConfig 的静态采集开关。
	 *
	 * @param isEnable true 打开, false 关闭
	 */
	static void setEnabled(final boolean isEnable) {
		LOGGER.info("### toggle debug for [ springmvc(+headers), tomcat78x, tomcat10x, httpclient, feign, dubbo2x, dubbo3x, jdbc ] to [ {} ]",
				isEnable);
		toggle(CLASS_NAME_FEIGN_PLUGIN_CONFIG, "COLLECT_REQUEST_BODY", isEnable);
		toggle(CLASS_NAME_JDBC_PLUGIN_CONFIG, "TRACE_SQL_PARAMETERS", isEnable);
		toggle(CLASS_NAME_HTTPCLIENT_PLUGIN_CONFIG, FIELD_COLLECT_HTTP_PARAMS, isEnable);
		toggle(CLASS_NAME_SPRINGMVC_PLUGIN_CONFIG, FIELD_COLLECT_HTTP_PARAMS, isEnable);
		toggle(CLASS_NAME_TOMCAT_7X_8X_PLUGIN_CONFIG, FIELD_COLLECT_HTTP_PARAMS, isEnable);
		toggle(CLASS_NAME_TOMCAT_10X_PLUGIN_CONFIG, FIELD_COLLECT_HTTP_PARAMS, isEnable);
		toggle(CLASS_NAME_DUBBO_2X_PLUGIN_CONFIG, "COLLECT_CONSUMER_ARGUMENTS", isEnable);
		toggle(CLASS_NAME_DUBBO_2X_PLUGIN_CONFIG, "COLLECT_PROVIDER_ARGUMENTS", isEnable);
		toggle(CLASS_NAME_DUBBO_3X_PLUGIN_CONFIG, "COLLECT_CONSUMER_ARGUMENTS", isEnable);
		toggle(CLASS_NAME_DUBBO_3X_PLUGIN_CONFIG, "COLLECT_PROVIDER_ARGUMENTS", isEnable);
		toggleSpringMvcHttpHeaders(isEnable);
		LOGGER.info("### toggle debug done.");
	}

	/**
	 * 反射改写指定 {@code PluginConfig} 的静态布尔开关字段。
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
	 * SpringMVC 的 HTTP 头采集: 开关不是布尔, 而是一份"要采集的 header 名清单"。
	 * <p>
	 * enable = 恢复 agent 配置({@code plugin.springmvc.include_http_headers})解析出的名单; disable = 置空。
	 * 名单来源刻意沿用 agent 自身配置, 本插件不再另立一份。
	 */
	private static void toggleSpringMvcHttpHeaders(final boolean isEnable) {
		try {
			final Class<?> cls = DynamicDebugRuntimeSwitch.class.getClassLoader()
					.loadClass(CLASS_NAME_SPRINGMVC_HTTP_CONFIG);
			// 必须先抓取(disable 也要抓):否则"先 disable 再 enable"会因名单已被清空而无法恢复。
			final List<String> enabledHeaderNames = captureHeaderNamesOnce(cls);
			final List<String> target = isEnable ? enabledHeaderNames : Collections.<String>emptyList();
			ReflectUtil.setFieldValue(cls, FIELD_INCLUDE_HTTP_HEADERS, target);
			LOGGER.info("### [ {}#{} ] -> size [ {} ]", CLASS_NAME_SPRINGMVC_HTTP_CONFIG, FIELD_INCLUDE_HTTP_HEADERS,
					target.size());
		} catch (ClassNotFoundException e) {
			LOGGER.warn("### plugin config [ {} ] not found, skipped. http headers unchanged.",
					CLASS_NAME_SPRINGMVC_HTTP_CONFIG);
		} catch (SecurityException | UtilException e) {
			LOGGER.warn(e, "### failed to toggle [ {}#{} ], skipped.", CLASS_NAME_SPRINGMVC_HTTP_CONFIG,
					FIELD_INCLUDE_HTTP_HEADERS);
		}
	}

	/**
	 * 首次触碰时把当前(即 agent 配置解析出的)header 名单抄一份留作"enable 时恢复值"。
	 * <p>
	 * enable / disable 都先调本方法 —— 若只在 enable 时抓取, 则"先 disable 再 enable"会因名单已被清空而无法恢复。
	 */
	private static synchronized List<String> captureHeaderNamesOnce(final Class<?> springMvcHttpConfigClass) {
		if (!headerNamesCaptured) {
			final Object current = ReflectUtil.getFieldValue(springMvcHttpConfigClass, FIELD_INCLUDE_HTTP_HEADERS);
			final List<String> names = new ArrayList<String>();
			if (current instanceof List) {
				for (Object name : (List<?>) current) {
					names.add(String.valueOf(name));
				}
			}
			headerNamesWhenEnabled = names;
			headerNamesCaptured = true;
			if (names.isEmpty()) {
				LOGGER.warn("### {}#{} is empty; enabling debug collects no http headers.",
						CLASS_NAME_SPRINGMVC_HTTP_CONFIG, FIELD_INCLUDE_HTTP_HEADERS);
			}
		}
		return headerNamesWhenEnabled;
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
