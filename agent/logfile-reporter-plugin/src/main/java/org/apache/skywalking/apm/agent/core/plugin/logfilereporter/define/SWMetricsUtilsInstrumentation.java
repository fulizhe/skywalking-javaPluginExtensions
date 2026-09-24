package org.apache.skywalking.apm.agent.core.plugin.logfilereporter.define;

import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.named;

import org.apache.skywalking.apm.agent.core.plugin.interceptor.StaticMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassStaticMethodsEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;
import org.apache.skywalking.apm.agent.core.plugin.match.NameMatch;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.description.method.MethodDescription;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatcher;

/**
 * 插件定义：增强 {@code SWMetricsUtils.statisticMetrics()} 与
 * {@code SWMetricsUtils.queryMetrics(condition)}，由 {@link MetricsExposeInterceptor}
 * 接管，经反射暴露 Trace 指标。
 * <p>
 * 范式同 {@link TraceParityUtilsInstrumentation}。
 * </p>
 */
public class SWMetricsUtilsInstrumentation extends ClassStaticMethodsEnhancePluginDefine {

    private static final String ENHANCE_CLASS = "org.apache.skywalking.apm.toolkit.SWMetricsUtils";
    private static final String METHOD_STATISTIC = "statisticMetrics";
    private static final String METHOD_QUERY = "queryMetrics";
    private static final String INTERCEPTOR_CLASS = "org.apache.skywalking.apm.agent.core.plugin.logfilereporter.MetricsExposeInterceptor";

    @Override
    protected ClassMatch enhanceClass() {
        return NameMatch.byName(ENHANCE_CLASS);
    }

    @Override
    public StaticMethodsInterceptPoint[] getStaticMethodsInterceptPoints() {
        return new StaticMethodsInterceptPoint[] { new StaticMethodsInterceptPoint() {
            @Override
            public ElementMatcher<MethodDescription> getMethodsMatcher() {
                return named(METHOD_STATISTIC).or(named(METHOD_QUERY));
            }

            @Override
            public String getMethodsInterceptor() {
                return INTERCEPTOR_CLASS;
            }

            @Override
            public boolean isOverrideArgs() {
                return false;
            }
        } };
    }
}
