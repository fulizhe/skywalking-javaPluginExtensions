package org.apache.skywalking.apm.agent.core.plugin.logfilereporter;

import java.lang.reflect.Method;
import java.util.Collections;
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
 * 自身状态暴露拦截器：接管 {@code SWSelfStatUtils.statisticSelf()}，
 * 经反射跨 ClassLoader 从 {@link LogFileTraceSegmentServiceClient} 取"监控自己"的运行指标。
 * <p>
 * 范式同 {@link MetricsExposeInterceptor}；读口按 10s 轮询，故异常一律吞掉并回一个
 * 形状一致的空壳（页面显示"读不到"，而不是 500 白屏）。
 * </p>
 */
public class SelfStatExposeInterceptor implements StaticMethodsAroundInterceptor {

    private static final ILog LOGGER = LogManager.getLogger(SelfStatExposeInterceptor.class);

    @Override
    public void beforeMethod(final Class clazz, final Method method, final Object[] allArguments,
            final Class<?>[] parameterTypes, final MethodInterceptResult result) {
        try {
            final TraceSegmentServiceClient client = (TraceSegmentServiceClient) ServiceManager.INSTANCE
                    .findService(TraceSegmentServiceClient.class);
            if (client == null) {
                result.defineReturnValue(emptySnapshot());
                return;
            }
            final Object value = ReflectUtil.invoke(client, "getSelfStat");
            result.defineReturnValue(value != null ? value : emptySnapshot());
        } catch (Exception e) {
            LOGGER.error(e, "### [SelfStat] SelfStatExposeInterceptor failed.");
            result.defineReturnValue(emptySnapshot());
        }
    }

    /** 读不到时的占位：形状与真实快照一致，页面按 {@code reporterEnabled=false} 显示"未挂载"。 */
    private static Map<String, Object> emptySnapshot() {
        final Map<String, Object> m = new LinkedHashMap<String, Object>(4);
        m.put("reporterEnabled", Boolean.FALSE);
        m.put("h2Enabled", Boolean.FALSE);
        m.put("dataLoss", Collections.emptyMap());
        m.put("hint", "未检测到 logfile-reporter-plugin:请确认已挂载插件且 agent 正常加载");
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
