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

package org.apache.skywalking.apm.plugin.dynamic;

import java.lang.reflect.Method;

import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.StaticMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelManager;
import org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient;
import org.apache.skywalking.apm.dependencies.io.grpc.Status;
import org.apache.skywalking.apm.dependencies.io.grpc.StatusRuntimeException;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ReflectUtil;

/**
 * 启用agent向server端发送数据
 */
public class DynamicEnableRuntimeInterceptor implements StaticMethodsAroundInterceptor {
	private static final ILog LOGGER = LogManager.getLogger(DynamicEnableRuntimeInterceptor.class);

	@Override
	public void beforeMethod(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			MethodInterceptResult result) {
		final boolean isEnable = Convert.toBool(allArguments[0]);

		LOGGER.info("============================================================");
		LOGGER.info("### toggle enable for agent-enable status of [ {} ] to [ {} ]", Config.Agent.SERVICE_NAME,
				isEnable);
		LOGGER.info("============================================================");

		enableAgentSendDataToServer(isEnable);
		enbaleAgentGrpcHeartBeat(isEnable);
	}

	private void enableAgentSendDataToServer(boolean isEnable) {
		// 修改我们自定义的配置项
		final TraceSegmentServiceClient traceSegmentServiceClient = ServiceManager.INSTANCE
				.findService(TraceSegmentServiceClient.class);
		if (!traceSegmentServiceClient.getClass().getName().contains("DyanmicEnabledTraceSegmentServiceClient")) {
			LOGGER.warn("### do not use [ DyanmicEnabledTraceSegmentServiceClient ]. skip set");
			return;
		}

		invokeMethod(traceSegmentServiceClient, "changeAgentEnableStatus", isEnable);
	}

	private void enbaleAgentGrpcHeartBeat(boolean isEnable) {
		// 让agent对服务端进行心跳检测, 为之后发送监控数据做准备
		final GRPCChannelManager grpcChannelManager = ServiceManager.INSTANCE.findService(GRPCChannelManager.class);
		if (!grpcChannelManager.getClass().getName().contains("DynamicEnabledGRPCChannelManager")) {
			LOGGER.warn("### do not use [ DynamicEnabledGRPCChannelManager ]. skip set");
			return;
		}

		final boolean currentGrpcHeartBeatResult = ReportStatusOfAgentPushToServerInterceptor.currentGrpcHeartBeatResult();
		if (currentGrpcHeartBeatResult == isEnable) {
			if (LOGGER.isInfoEnable()) {
				LOGGER.info("### current grpc-heartbeat status is [ {} ], do not need change", currentGrpcHeartBeatResult);
			}			
			return;
		}

		LOGGER.warn("### begin beg the grpc to heart beat of [ {} ]", Config.Agent.SERVICE_NAME);
		// 让 reconnect 字段置为 true, 为重连做准备
		invokeMethod(grpcChannelManager, "reportError", new StatusRuntimeException(Status.UNAVAILABLE));
		// 马上重连一次, 进行验活
		invokeMethod(grpcChannelManager, "run");
	}

	private void invokeMethod(Object obj, final String methodName, Object... parameters) {
		ReflectUtil.invoke(obj, methodName, parameters);
	}

	@Override
	public Object afterMethod(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			Object ret) {
		return ReportStatusOfAgentPushToServerInterceptor.currentGrpcHeartBeatResult();
	}

	@Override
	public void handleMethodException(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			Throwable t) {
		ContextManager.activeSpan().errorOccurred().log(t);
	}
}
