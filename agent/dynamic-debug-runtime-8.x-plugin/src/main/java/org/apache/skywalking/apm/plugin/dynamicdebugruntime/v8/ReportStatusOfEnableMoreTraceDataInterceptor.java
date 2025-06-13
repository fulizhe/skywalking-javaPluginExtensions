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

package org.apache.skywalking.apm.plugin.dynamicdebugruntime.v8;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.StaticMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelManager;
import org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.exceptions.UtilException;
import cn.hutool.core.util.ReflectUtil;

/**
 * <p>
 * 针对"启用更多链路信息捕获"功能, 向外界提供可观测性
 */
public class ReportStatusOfEnableMoreTraceDataInterceptor implements StaticMethodsAroundInterceptor {
	private static final ILog LOGGER = LogManager.getLogger(ReportStatusOfEnableMoreTraceDataInterceptor.class);

	@Override
	public void beforeMethod(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			MethodInterceptResult result) {

		Map<String, Boolean> resultMap = new HashMap<>();
		resultMap.put("grpc-heartbeat", false);
		resultMap.put("enbaleSendDataToServer", true);

		result.defineReturnValue(resultMap);
	}

	private static void toggleFeign(Map<String, Object> result) {
		try {
			ClassLoader cl = DynamicDebugRuntimeInterceptor.class.getClassLoader();
			Class<?> cls = cl.loadClass(DynamicDebugRuntimeInterceptor.CLASS_NAME_FEIGN_PLUGIN_CONFIG);
			final String fieldName = "COLLECT_REQUEST_BODY";
			result.put("feign." + fieldName, ReflectUtil.getFieldValue(cls, fieldName));
		} catch (SecurityException | ClassNotFoundException | UtilException e) {
			throw new RuntimeException(e);
		}
	}


	static final String CLASS_NAME_FEIGN_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.feign.http.v9.FeignPluginConfig$Plugin$Feign";
	static final String CLASS_NAME_JDBC_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.jdbc.JDBCPluginConfig$Plugin$JDBC";
	static final String CLASS_NAME_SPRINGMVC_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.spring.mvc.commons.SpringMVCPluginConfig$Plugin$SpringMVC";
	static final String CLASS_NAME_HTTPCLIENT_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig$Plugin$HttpClient";
	static final String CLASS_NAME_TOMCAT_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.tomcat78x.TomcatPluginConfig$Plugin$Tomcat";
	static final String CLASS_NAME_DUBBO_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig$Plugin$HttpClient";
	
	
		
	
	private static void toggleJdbc(Map<String, Object> result) {
		try {
			ClassLoader cl = DynamicDebugRuntimeInterceptor.class.getClassLoader();
			Class<?> cls = cl.loadClass(CLASS_NAME_JDBC_PLUGIN_CONFIG);
			final Boolean TRACE_SQL_PARAMETERS = Convert.toBool(ReflectUtil.getFieldValue(cls, "TRACE_SQL_PARAMETERS"));
		} catch (SecurityException | ClassNotFoundException | UtilException e) {
			throw new RuntimeException(e);
		}
	}

	private static void toggleHttpclient(final boolean isEnable) {
		try {
			ClassLoader cl = DynamicDebugRuntimeInterceptor.class.getClassLoader();
			Class<?> cls = cl.loadClass(CLASS_NAME_HTTPCLIENT_PLUGIN_CONFIG);
			// final Boolean COLLECT_HTTP_PARAMS =
			// Convert.toBool(ReflectUtil.getFieldValue(cls, "COLLECT_HTTP_PARAMS"));
			ReflectUtil.setFieldValue(cls, "COLLECT_HTTP_PARAMS", isEnable);
		} catch (SecurityException | ClassNotFoundException | UtilException e) {
			throw new RuntimeException(e);
		}
	}

	private static void toggleSpringMvc(final boolean isEnable) {
		try {
			ClassLoader cl = DynamicDebugRuntimeInterceptor.class.getClassLoader();
			Class<?> cls = cl.loadClass(CLASS_NAME_SPRINGMVC_PLUGIN_CONFIG);
			// final Boolean COLLECT_HTTP_PARAMS =
			// Convert.toBool(ReflectUtil.getFieldValue(cls, "COLLECT_HTTP_PARAMS"));
			ReflectUtil.setFieldValue(cls, "COLLECT_HTTP_PARAMS", isEnable);
		} catch (SecurityException | ClassNotFoundException | UtilException e) {
			throw new RuntimeException(e);
		}
	}

	private void toggleTomcat(final boolean isEnable) {
		try {
			ClassLoader cl = DynamicDebugRuntimeInterceptor.class.getClassLoader();
			Class<?> cls = cl.loadClass(CLASS_NAME_TOMCAT_PLUGIN_CONFIG);
			// final Boolean COLLECT_HTTP_PARAMS =
			// Convert.toBool(ReflectUtil.getFieldValue(cls, "COLLECT_HTTP_PARAMS"));
			ReflectUtil.setFieldValue(cls, "COLLECT_HTTP_PARAMS", isEnable);
		} catch (SecurityException | ClassNotFoundException | UtilException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Object afterMethod(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			Object ret) {
		return ret;
	}

	@Override
	public void handleMethodException(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			Throwable t) {
		ContextManager.activeSpan().errorOccurred().log(t);
	}
}
