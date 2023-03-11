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
import cn.hutool.core.util.ReflectUtil;

/**
 * <p> 针对"启用agent向server端发送数据"功能, 向外界提供可观测性
 * <p> 参考自: {@code }
 */
public class ReportStatusOfAgentPushToServerInterceptor implements StaticMethodsAroundInterceptor {
	private static final ILog LOGGER = LogManager.getLogger(ReportStatusOfAgentPushToServerInterceptor.class);

	@Override
	public void beforeMethod(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			MethodInterceptResult result) {

		final boolean currentGrpcHeartBeatResult = currentGrpcHeartBeatResult();
		Map<String, Boolean> resultMap = new HashMap<>();
		resultMap.put("grpc-heartbeat", currentGrpcHeartBeatResult);
		resultMap.put("enbaleSendDataToServer", isEnbaleSendDataToServer());

		result.defineReturnValue(resultMap);
	}

	@Override
	public Object afterMethod(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			Object ret) {
		return ret;
	}

	static boolean currentGrpcHeartBeatResult() {
		// 参考: PrintTraceIdInterceptor.java
		final GRPCChannelManager grpcChannelManager = ServiceManager.INSTANCE.findService(GRPCChannelManager.class);
		// 返回值, 告知调用者当前agent是否已经可以正确向服务端推送监控数据
		return (!Convert.toBool(ReflectUtil.getFieldValue(grpcChannelManager, "reconnect")));		
	}

	static boolean isEnbaleSendDataToServer() {
		TraceSegmentServiceClient findService = (TraceSegmentServiceClient) ServiceManager.INSTANCE
				.findService(TraceSegmentServiceClient.class);
		final Object configWatcher = ReflectUtil.invoke(findService, "getConfigWatcher");
		return Convert.toBool(ReflectUtil.invoke(configWatcher, "isEnbaleSendDataToServer"));
	}

	@Override
	public void handleMethodException(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			Throwable t) {
		ContextManager.activeSpan().errorOccurred().log(t);
	}
}
