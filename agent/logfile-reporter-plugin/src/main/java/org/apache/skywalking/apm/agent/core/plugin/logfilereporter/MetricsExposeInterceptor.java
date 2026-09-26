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
 * Trace 指标暴露拦截器：接管 {@code SWMetricsUtils.statisticMetrics()}、
 * {@code SWMetricsUtils.queryMetrics(condition)} 与 {@code SWMetricsUtils.extremeTraces()}，
 * 经反射跨 ClassLoader 从 {@link LogFileTraceSegmentServiceClient} 取指标数据。
 * <p>
 * 范式同 {@link TraceParityStatusExposeInterceptor}。
 * </p>
 */
public class MetricsExposeInterceptor implements StaticMethodsAroundInterceptor {

    private static final ILog LOGGER = LogManager.getLogger(MetricsExposeInterceptor.class);

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
            final Object value;
            if ("queryMetrics".equals(method.getName())) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> condition = (allArguments != null && allArguments.length > 0
                        && allArguments[0] instanceof Map) ? (Map<String, Object>) allArguments[0] : null;
                value = ReflectUtil.invoke(client, "queryMetrics", condition);
            } else if ("extremeTraces".equals(method.getName())) {
                value = ReflectUtil.invoke(client, "getExtremeTraces");
            } else {
                value = ReflectUtil.invoke(client, "getMetricsStatus");
            }
            result.defineReturnValue(value != null ? value : defaultFor(method.getName()));
        } catch (Exception e) {
            LOGGER.error(e, "### [Metrics] MetricsExposeInterceptor failed.");
            result.defineReturnValue(defaultFor(method.getName()));
        }
    }

    private static Object defaultFor(final String methodName) {
        if ("queryMetrics".equals(methodName)) {
            final Map<String, Object> empty = new HashMap<String, Object>();
            empty.put("resolution", "minute");
            empty.put("rows", Collections.emptyList());
            empty.put("count", 0);
            empty.put("truncated", false);
            return empty;
        }
        if ("extremeTraces".equals(methodName)) {
            final Map<String, Object> empty = new HashMap<String, Object>();
            empty.put("selector", "absent");
            empty.put("rows", Collections.emptyList());
            empty.put("count", 0);
            return empty;
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
