/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// 本文件与官方 apm-httpclient-5.x-plugin (v9.4.0) 中的 HttpAsyncClientDoExecuteInterceptor 同源,
// 未修改, 保持原样(异步路径不加入参/出参采集)。
// 原因: 本 override 插件整包替代官方插件, 异步 tracing 必须由这份副本原样延续。
package org.apache.skywalking.apm.plugin.override.httpclient.v5;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.plugin.override.httpclient.v5.wrapper.AsyncResponseConsumerWrapper;
import org.apache.skywalking.apm.plugin.override.httpclient.v5.wrapper.FutureCallbackWrapper;

import java.lang.reflect.Method;

public class HttpAsyncClientDoExecuteInterceptor implements InstanceMethodsAroundInterceptor {

    @Override
    public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
            MethodInterceptResult result) throws Throwable {
        AsyncResponseConsumer consumer = (AsyncResponseConsumer) allArguments[2];
        HttpContext context = (HttpContext) allArguments[4];
        FutureCallback callback = (FutureCallback) allArguments[5];
        allArguments[2] = new AsyncResponseConsumerWrapper(consumer);
        allArguments[5] = new FutureCallbackWrapper(callback);
        if (ContextManager.isActive()) {
            context.setAttribute(Constants.SKYWALKING_CONTEXT_SNAPSHOT, ContextManager.capture());
        }
    }

    @Override
    public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
            Object ret) throws Throwable {
        return ret;
    }

    @Override
    public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
            Class<?>[] argumentsTypes, Throwable t) {

    }
}
