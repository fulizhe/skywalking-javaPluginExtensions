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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.skywalking.apm.agent.core.plugin.logfilereporter;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.StaticMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient;

import cn.hutool.core.util.ReflectUtil;

/**
 * 内存热层状态暴露拦截器：接 {@code SWLogfileReporterUtils.hotLayerStat()}，
 * 经反射跨 ClassLoader 读 {@link LogFileTraceSegmentServiceClient} 的
 * {@code getHotLayerStat()}——"进程内缓存里存了多少、覆盖了多长时间"。
 *
 * <p>与 {@link LogfileReporterStatusExposeInterceptor}（{@code statisticStatus}）分开成
 * 两个拦截点，而不是往 status 的返回里塞新键：status 的返回形状已被现有调用方依赖，
 * 加键等于改既有契约；分开则status 的形状与行为完全不变。</p>
 *
 * <p>范式同 {@code LogfileReporterStatusExposeInterceptor}：必须用反射取 service，
 * 不能强转成真实类型（跨 ClassLoader）；异常吞掉并回同形状空壳，读口不应因
 * 可观测性代码出错而抛给调用方。</p>
 */
public class HotLayerStatExposeInterceptor implements StaticMethodsAroundInterceptor {

	private static final ILog LOGGER = LogManager.getLogger(HotLayerStatExposeInterceptor.class);

	@Override
	public void beforeMethod(final Class clazz, final Method method, final Object[] allArguments,
			final Class<?>[] parameterTypes, final MethodInterceptResult result) {
		try {
			// 这里必须使用反射来获取, 不要尝试进行类型转换为真实类型
			final TraceSegmentServiceClient client = (TraceSegmentServiceClient) ServiceManager.INSTANCE
					.findService(TraceSegmentServiceClient.class);
			if (client == null) {
				result.defineReturnValue(emptySnapshot());
				return;
			}
			final Object value = ReflectUtil.invoke(client, "getHotLayerStat");
			result.defineReturnValue(value != null ? value : emptySnapshot());
		} catch (Exception e) {
			LOGGER.error(e, "### [HotLayer] HotLayerStatExposeInterceptor failed.");
			result.defineReturnValue(emptySnapshot());
		}
	}

	/**
	 * 读不到时的占位：形状与真实快照一致、{@code enabled=false}，
	 * 调用方按"未启用"展示即可，不必判空。
	 */
	private static Map<String, Object> emptySnapshot() {
		final Map<String, Object> m = new LinkedHashMap<String, Object>(8);
		m.put("enabled", Boolean.FALSE);
		m.put("traces", Integer.valueOf(0));
		m.put("segments", Long.valueOf(0L));
		m.put("spans", Long.valueOf(0L));
		m.put("oldestSpanTimeMs", Long.valueOf(-1L));
		m.put("newestSpanTimeMs", Long.valueOf(-1L));
		m.put("coveredSpanMs", Long.valueOf(-1L));
		m.put("estimatedBytes", Long.valueOf(0L));
		return m;
	}

	@Override
	public Object afterMethod(final Class clazz, final Method method, final Object[] allArguments,
			final Class<?>[] parameterTypes, final Object ret) {
		return ret;
	}

	@Override
	public void handleMethodException(final Class clazz, final Method method, final Object[] allArguments,
			final Class<?>[] parameterTypes, final Throwable t) {
		ContextManager.activeSpan().errorOccurred().log(t);
	}
}