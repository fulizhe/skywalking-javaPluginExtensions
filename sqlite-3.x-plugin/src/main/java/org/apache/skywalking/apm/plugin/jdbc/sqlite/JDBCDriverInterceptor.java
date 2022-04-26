package org.apache.skywalking.apm.plugin.jdbc.sqlite;

import java.lang.reflect.Method;

import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.network.trace.component.OfficialComponent;

/**
 * <p> COPY FROM {@code JDBCDriverInterceptor}
 * 
 * <p> {@code URLParser.parser(String url) } 当前无法解析sqlite链接
 */
public class JDBCDriverInterceptor implements InstanceMethodsAroundInterceptor {

	@Override
	public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
			MethodInterceptResult result) throws Throwable {

	}

	@Override
	public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
			Object ret) throws Throwable {
		if (ret != null && ret instanceof EnhancedInstance) {
			((EnhancedInstance) ret).setSkyWalkingDynamicField(dd((String) allArguments[0]));
		}

		return ret;
	}

	private org.apache.skywalking.apm.plugin.jdbc.trace.ConnectionInfo dd(final String url) {
		return new org.apache.skywalking.apm.plugin.jdbc.trace.ConnectionInfo(new OfficialComponent(9998, "sqlite"),
				"sqlite", "localhost", -1, url);
	}

	@Override
	public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
			Class<?>[] argumentsTypes, Throwable t) {

	}
}
