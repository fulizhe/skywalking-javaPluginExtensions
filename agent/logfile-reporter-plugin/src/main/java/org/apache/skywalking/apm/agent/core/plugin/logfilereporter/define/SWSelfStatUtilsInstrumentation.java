package org.apache.skywalking.apm.agent.core.plugin.logfilereporter.define;

import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.named;

import org.apache.skywalking.apm.agent.core.plugin.interceptor.StaticMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassStaticMethodsEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;
import org.apache.skywalking.apm.agent.core.plugin.match.NameMatch;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.description.method.MethodDescription;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatcher;

/**
 * 插件定义：增强 {@code SWSelfStatUtils.statisticSelf()}，
 * 由 {@link SelfStatExposeInterceptor} 接管，经反射暴露"监控自身"的运行指标
 * （环形文件写指针、H2 行数与内存估算、DataCarrier 丢弃数、未提交积压）。
 * <p>
 * 范式同 {@link SWMetricsUtilsInstrumentation}。单独一条读口而不并进 {@code trace-parity} /
 * {@code metrics}：那两者的字段在持续演进，而"自身开销"要能长期稳定地被引用（页面按 10s 轮询它）。
 * </p>
 */
public class SWSelfStatUtilsInstrumentation extends ClassStaticMethodsEnhancePluginDefine {

    private static final String ENHANCE_CLASS = "org.apache.skywalking.apm.toolkit.SWSelfStatUtils";
    private static final String METHOD_STATISTIC_SELF = "statisticSelf";
    private static final String INTERCEPTOR_CLASS = "org.apache.skywalking.apm.agent.core.plugin.logfilereporter.SelfStatExposeInterceptor";

    @Override
    protected ClassMatch enhanceClass() {
        return NameMatch.byName(ENHANCE_CLASS);
    }

    @Override
    public StaticMethodsInterceptPoint[] getStaticMethodsInterceptPoints() {
        return new StaticMethodsInterceptPoint[] { new StaticMethodsInterceptPoint() {
            @Override
            public ElementMatcher<MethodDescription> getMethodsMatcher() {
                return named(METHOD_STATISTIC_SELF);
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
