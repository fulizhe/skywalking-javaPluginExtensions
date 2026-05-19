package org.apache.skywalking.apm.plugin.override.httpclient.v4;

import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.StaticMethodsAroundInterceptor;

import java.lang.reflect.Method;

public class HttpClientCollectStatusExposeInterceptor implements StaticMethodsAroundInterceptor {
    @Override
    public void beforeMethod(final Class clazz,
                             final Method method,
                             final Object[] allArguments,
                             final Class<?>[] parameterTypes,
                             final MethodInterceptResult result) {
        result.defineReturnValue(HttpClientCollectionSwitch.currentStatus());
    }

    @Override
    public Object afterMethod(final Class clazz,
                              final Method method,
                              final Object[] allArguments,
                              final Class<?>[] parameterTypes,
                              final Object ret) {
        return HttpClientCollectionSwitch.currentStatus();
    }

    @Override
    public void handleMethodException(final Class clazz,
                                      final Method method,
                                      final Object[] allArguments,
                                      final Class<?>[] parameterTypes,
                                      final Throwable t) {
        ContextManager.activeSpan().errorOccurred().log(t);
    }
}
