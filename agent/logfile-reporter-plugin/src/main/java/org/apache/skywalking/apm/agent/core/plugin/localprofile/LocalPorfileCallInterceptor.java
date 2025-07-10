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

package org.apache.skywalking.apm.agent.core.plugin.localprofile;

import java.lang.reflect.Method;
import java.util.Map;

import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.commands.CommandExecutionException;
import org.apache.skywalking.apm.agent.core.commands.CommandExecutorService;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.StaticMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.profile.ProfileTask;
import org.apache.skywalking.apm.network.language.profile.v3.ProfileTaskFinishReport;
import org.apache.skywalking.apm.network.trace.component.command.ProfileTaskCommand;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;

/**
 * <p>
 * profile 性能剖析
 * <p>
 * 参考自: {@code }
 */
public class LocalPorfileCallInterceptor implements StaticMethodsAroundInterceptor {
	private static final ILog LOGGER = LogManager.getLogger(LocalPorfileCallInterceptor.class);

	@Override
	public void beforeMethod(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			MethodInterceptResult result) {

		// SkyWalking 的 Profile 功能用于分析特定请求链路上的性能瓶颈，它通过在指定端点上进行采样，收集方法执行栈信息，帮助开发者定位性能问题。

		final Map<String, Object> methodParams = Convert.toMap(String.class, Object.class, allArguments[0]);

		LOGGER.info("============================================================");
		LOGGER.info("### start profile [ {} ] by [ {} ]", Config.Agent.SERVICE_NAME, methodParams);
		LOGGER.info("============================================================");

		final CommandExecutorService service = (CommandExecutorService) ServiceManager.INSTANCE
				.findService(CommandExecutorService.class);

		final String serialNumber = IdUtil.fastSimpleUUID();
		// 唯一标识一个 Profile 任务的 ID。 SkyWalking 使用 taskId 来区分不同的 Profile 任务。
		final String taskId = StrUtil.format("profile-task-{}-{}",
				DateUtil.format(DateUtil.date(), DatePattern.PURE_DATETIME_MS_PATTERN), serialNumber);
		// 指定要进行 Profile 分析的端点名称。 端点通常是指一个 HTTP 接口、gRPC 方法、或其他服务入口点。
		// 典型值:
		// 对于 HTTP 接口，可以是 "/users/{id}" 或 "/products"。
		// 对于 gRPC 方法，可以是 "com.example.UserService/GetUser"。
		// 重要性: endpointName 必须与实际端点名称完全匹配，否则 Profile 任务不会生效。
		final String endpointName = MapUtil.getStr(methodParams, "endpointName", "/statistic");
		// Profile 任务的持续时间，单位是分钟。 指定 Profile 任务运行多长时间后自动停止。
		final int duration = MapUtil.getInt(methodParams, "duration", 5);
		// 只有执行时间超过此阈值的方法调用才会被记录，单位是毫秒。 用于过滤掉执行时间较短的方法调用，减少数据量。
		final int minDurationThreshold = MapUtil.getInt(methodParams, "minDurationThreshold", 1000);
		// Agent 将 Profile 数据转储到 SkyWalking OAP 的周期，单位是毫秒。 指定 Agent 多长时间向 OAP 报告一次
		// Profile 数据。
		// 典型值: 通常设置为 1000 到 5000 毫秒 (1 到 5 秒)。
		final int dumpPeriod = MapUtil.getInt(methodParams, "dumpPeriod", 1000);
		// 每个方法调用栈的最大采样数量。 用于限制 Profile 数据的总量，防止 Agent 内存溢出。
		// 典型值: 通常设置为 100 到 500。
		//  "ProfileTaskExecutionService : check command error, cannot process this profile task. reason: max sampling count must less than 10" 
		final int maxSamplingCount = MapUtil.getInt(methodParams, "maxSamplingCount", 9);
		final long startTime = System.currentTimeMillis();
		final long createTime = System.currentTimeMillis() + 1;
		ProfileTaskCommand command = new ProfileTaskCommand(serialNumber, taskId, endpointName, duration,
				minDurationThreshold, dumpPeriod, maxSamplingCount, startTime, createTime);
		// ProfileTaskCommandExecutor 中执行; 最终在 ProfileTaskExecutionService执行profile,
		// 使用ProfileTaskChannelService将结果发给OAP
		// 真正的profile逻辑位于: ProfileThread.java, ThreadProfiler.java
		// 关键方法：
		// 1. ProfileTaskChannelService.addProfilingSnapshot(...) #
		// ProfileThread中回调这个方法将profile结果收集起来
		try {
			service.execute(command);
		} catch (CommandExecutionException e) {
			LOGGER.error(e.getMessage(), e);
		}

		result.defineReturnValue(null);
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
