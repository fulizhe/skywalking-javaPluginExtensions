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
import java.util.HashMap;
import java.util.Map;

import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.jvm.JVMMetricsSender;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.StaticMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient;

import cn.hutool.core.util.ReflectUtil;

/**
 * <p>
 * 针对"启用agent保存数据到本地"功能, 向外界提供可观测性
 * <p>
 * 参考自: {@code }
 */
public class LogfileReporterStatusExposeInterceptor implements StaticMethodsAroundInterceptor {
	private static final ILog LOGGER = LogManager.getLogger(LogfileReporterStatusExposeInterceptor.class);

	@Override
	public void beforeMethod(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			MethodInterceptResult result) {

		LOGGER.info("### Status Expose");

		final TraceSegmentServiceClient client = (TraceSegmentServiceClient) ServiceManager.INSTANCE
				.findService(TraceSegmentServiceClient.class);
		// 这里必须使用反射来获取, 不要尝试进行类型转换为真实类型
		final Object logfileStatMap = ReflectUtil.invoke(client, "getLogfileStatMap");
		final Object enable = ReflectUtil.invoke(client, "isEnbaleLogfileReporter");
		final Object maxLogSize = ReflectUtil.getFieldValue(client, "maxLogSize");

		final JVMMetricsSender sender = (JVMMetricsSender) ServiceManager.INSTANCE
				.findService(JVMMetricsSender.class);
		// 增加日志输出，确保sender对象已成功获取
		if (sender == null) {
			LOGGER.warn("### JVMMetricsLocalSender 获取失败, sender为null");
		} else {
			LOGGER.info("### JVMMetricsLocalSender 获取成功: {}", sender.getClass().getName());
		}

		Map<String, Object> resultMap = new HashMap<>();
		resultMap.put("data", logfileStatMap);
		resultMap.put("enbaleLogfileReporter", enable);
		resultMap.put("maxLogSize", maxLogSize);
		// 这里必须使用反射来获取, 不要尝试进行类型转换为真实类型JVMMetricsLocalSender
		resultMap.put("jvm", ReflectUtil.invoke(sender, "getMetrics"));

		result.defineReturnValue(resultMap);
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
