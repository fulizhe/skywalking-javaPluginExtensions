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

package org.apache.skywalking.apm.agent.core.plugin.logfilereporter;

import java.lang.reflect.Method;
import java.util.Map;

import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.StaticMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ReflectUtil;

/**
 * 启用agent向server端发送数据
 */
public class LogfileReporterEnableRuntimeInterceptor implements StaticMethodsAroundInterceptor {
	private static final ILog LOGGER = LogManager.getLogger(LogfileReporterEnableRuntimeInterceptor.class);

	@Override
	public void beforeMethod(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			MethodInterceptResult result) {
		final Map<String, Object> methodParams = Convert.toMap(String.class, Object.class, allArguments[0]);

		LOGGER.info("============================================================");
		LOGGER.info("### enable for agent-enable status of [ {} ] by [ {} ]", Config.Agent.SERVICE_NAME, methodParams);
		LOGGER.info("============================================================");

		final TraceSegmentServiceClient traceSegmentServiceClient = ServiceManager.INSTANCE
				.findService(TraceSegmentServiceClient.class);
		if (!traceSegmentServiceClient.getClass().getName().contains("LogFileTraceSegmentServiceClient")) {
			LOGGER.warn("### do not use [ LogFileTraceSegmentServiceClient ]. skip set");
			return;
		}

		invokeMethod(traceSegmentServiceClient, "toggleEnable", true);
	}

	private void invokeMethod(Object obj, final String methodName, Object... parameters) {
		ReflectUtil.invoke(obj, methodName, parameters);
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
