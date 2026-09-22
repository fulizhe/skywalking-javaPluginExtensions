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
import java.util.Collections;
import java.util.HashMap;
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
 * 对账状态暴露拦截器：接管 {@code SWTraceParityUtils.statisticParity()}，
 * 经反射跨 ClassLoader 从 {@link LogFileTraceSegmentServiceClient} 取对账数据。
 * <p>
 * 范式同 {@link LogfileReporterStatusExposeInterceptor}。
 * </p>
 */
public class TraceParityStatusExposeInterceptor implements StaticMethodsAroundInterceptor {

    private static final ILog LOGGER = LogManager.getLogger(TraceParityStatusExposeInterceptor.class);

    @Override
    public void beforeMethod(final Class clazz, final Method method, final Object[] allArguments,
            final Class<?>[] parameterTypes, final MethodInterceptResult result) {
        try {
            final TraceSegmentServiceClient client = (TraceSegmentServiceClient) ServiceManager.INSTANCE
                    .findService(TraceSegmentServiceClient.class);
            if (client == null) {
                result.defineReturnValue(defaultFor(method.getName()));
                return;
            }
            final String name = method.getName();
            final Object value;
            if ("queryTrace".equals(name)) {
                final String traceId = (allArguments != null && allArguments.length > 0 && allArguments[0] != null)
                        ? String.valueOf(allArguments[0]) : null;
                value = ReflectUtil.invoke(client, "getTraceView", traceId);
            } else if ("recentTraces".equals(name)) {
                final Integer limit = (allArguments != null && allArguments.length > 0 && allArguments[0] instanceof Integer)
                        ? (Integer) allArguments[0] : Integer.valueOf(20);
                value = ReflectUtil.invoke(client, "getRecentTraces", limit);
            } else {
                value = ReflectUtil.invoke(client, "getParityStatus");
            }
            result.defineReturnValue(value != null ? value : defaultFor(name));
        } catch (Exception e) {
            LOGGER.error(e, "### [H2Shadow] TraceParityStatusExposeInterceptor failed.");
            result.defineReturnValue(defaultFor(method.getName()));
        }
    }

    private static Object defaultFor(final String methodName) {
        if ("recentTraces".equals(methodName)) {
            return Collections.emptyList();
        }
        return new HashMap<String, Object>();
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
