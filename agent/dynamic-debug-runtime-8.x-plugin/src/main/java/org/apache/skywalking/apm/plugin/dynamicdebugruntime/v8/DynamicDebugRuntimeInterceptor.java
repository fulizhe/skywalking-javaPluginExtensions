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
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.StaticMethodsAroundInterceptor;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.exceptions.UtilException;
import cn.hutool.core.util.ReflectUtil;

/**
 * 
 */
public class DynamicDebugRuntimeInterceptor implements StaticMethodsAroundInterceptor {
	private static final ILog LOGGER = LogManager.getLogger(DynamicDebugRuntimeInterceptor.class);

    @Override
    public void beforeMethod(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
        MethodInterceptResult result) {
		LOGGER.info("==========================================");
		LOGGER.info("toggle debug for [ " + "feign, jdbc, httpclient, springmvc, tomcat" + " ]");
		toggleFeign();
		toggleJdbc();
		toggleHttpclient();
		toggleSpringMvc();
		toggleTomcat();
		//toggleDubbo();		
		LOGGER.info("==========================================");		
	}

	private static final String CLASS_NAME_FEIGN_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.feign.http.v9.FeignPluginConfig$Plugin$Feign";
	private static final String CLASS_NAME_JDBC_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.jdbc.JDBCPluginConfig$Plugin$JDBC";
	private static final String CLASS_NAME_SPRINGMVC_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.spring.mvc.commons.SpringMVCPluginConfig$Plugin$SpringMVC";
	private static final String CLASS_NAME_HTTPCLIENT_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig$Plugin$HttpClient";
	private static final String CLASS_NAME_TOMCAT_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.tomcat78x.TomcatPluginConfig$Plugin$Tomcat";
	private static final String CLASS_NAME_DUBBO_PLUGIN_CONFIG = "org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig$Plugin$HttpClient";
	
	
	
	private static void toggleFeign() {
		try {			
			ClassLoader cl = DynamicDebugRuntimeInterceptor.class.getClassLoader();
			Class<?> cls = cl.loadClass(CLASS_NAME_FEIGN_PLUGIN_CONFIG);
			final Boolean COLLECT_REQUEST_BODY = Convert.toBool(ReflectUtil.getFieldValue(cls, "COLLECT_REQUEST_BODY"));
			ReflectUtil.setFieldValue(cls, "COLLECT_REQUEST_BODY", !COLLECT_REQUEST_BODY);
		} catch (SecurityException | ClassNotFoundException | UtilException e) {
			throw new RuntimeException(e);
		}
	}
	
	private static void toggleJdbc() {
		try {			
			ClassLoader cl = DynamicDebugRuntimeInterceptor.class.getClassLoader();
			Class<?> cls = cl.loadClass(CLASS_NAME_JDBC_PLUGIN_CONFIG);
			final Boolean TRACE_SQL_PARAMETERS = Convert.toBool(ReflectUtil.getFieldValue(cls, "TRACE_SQL_PARAMETERS"));
			ReflectUtil.setFieldValue(cls, "TRACE_SQL_PARAMETERS", !TRACE_SQL_PARAMETERS);
		} catch (SecurityException | ClassNotFoundException | UtilException e) {
			throw new RuntimeException(e);
		}
	}	
	
	private static void toggleHttpclient() {
		try {			
			ClassLoader cl = DynamicDebugRuntimeInterceptor.class.getClassLoader();
			Class<?> cls = cl.loadClass(CLASS_NAME_HTTPCLIENT_PLUGIN_CONFIG);
			final Boolean COLLECT_HTTP_PARAMS = Convert.toBool(ReflectUtil.getFieldValue(cls, "COLLECT_HTTP_PARAMS"));
			ReflectUtil.setFieldValue(cls, "COLLECT_HTTP_PARAMS", !COLLECT_HTTP_PARAMS);
		} catch (SecurityException | ClassNotFoundException | UtilException e) {
			throw new RuntimeException(e);
		}
	}		
	
	private static void toggleSpringMvc() {
		try {			
			ClassLoader cl = DynamicDebugRuntimeInterceptor.class.getClassLoader();
			Class<?> cls = cl.loadClass(CLASS_NAME_SPRINGMVC_PLUGIN_CONFIG);
			final Boolean COLLECT_HTTP_PARAMS = Convert.toBool(ReflectUtil.getFieldValue(cls, "COLLECT_HTTP_PARAMS"));
			ReflectUtil.setFieldValue(cls, "COLLECT_HTTP_PARAMS", !COLLECT_HTTP_PARAMS);
		} catch (SecurityException | ClassNotFoundException | UtilException e) {
			throw new RuntimeException(e);
		}
	}
	
	private void toggleTomcat() {
		try {			
			ClassLoader cl = DynamicDebugRuntimeInterceptor.class.getClassLoader();
			Class<?> cls = cl.loadClass(CLASS_NAME_TOMCAT_PLUGIN_CONFIG);
			final Boolean COLLECT_HTTP_PARAMS = Convert.toBool(ReflectUtil.getFieldValue(cls, "COLLECT_HTTP_PARAMS"));
			ReflectUtil.setFieldValue(cls, "COLLECT_HTTP_PARAMS", !COLLECT_HTTP_PARAMS);
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
