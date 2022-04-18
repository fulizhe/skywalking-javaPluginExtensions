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

import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.exceptions.UtilException;
import cn.hutool.core.util.ReflectUtil;

/**
 * 
 */
public class DynamicDebugRuntimeInterceptor implements InstanceMethodsAroundInterceptor {
	private static final ILog LOGGER = LogManager.getLogger(DynamicDebugRuntimeInterceptor.class);

	@Override
	public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
			MethodInterceptResult result) throws Throwable {
		LOGGER.info("==========================================");
		LOGGER.info(this.getClass().getClassLoader().toString());
		LOGGER.info(this.getClass().getClassLoader().getParent().toString());
		ddd();
		LOGGER.info("==========================================");		
	}

	private static String className = "org.apache.skywalking.apm.plugin.feign.http.v9.FeignPluginConfig$Plugin$Feign";

	private static void ddd() {
		try {			
			ClassLoader cl = DynamicDebugRuntimeInterceptor.class.getClassLoader();
			Class<?> cls = cl.loadClass(className);
			final Boolean COLLECT_REQUEST_BODY = Convert.toBool(ReflectUtil.getFieldValue(cls, "COLLECT_REQUEST_BODY"));
			LOGGER.info("### COLLECT_REQUEST_BODY: [ {} ]", COLLECT_REQUEST_BODY);
			ReflectUtil.setFieldValue(cls, "COLLECT_REQUEST_BODY", !COLLECT_REQUEST_BODY);
		} catch (SecurityException | ClassNotFoundException | UtilException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
			Object ret) throws Throwable {
		ContextManager.stopSpan();
		return ret;
	}

	@Override
	public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
			Class<?>[] argumentsTypes, Throwable t) {
		ContextManager.activeSpan().errorOccurred().log(t);
	}
}
