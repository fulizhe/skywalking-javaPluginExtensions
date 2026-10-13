package org.apache.skywalking.apm.plugin.dynamicdebugruntime.v9.define;

import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.named;
import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import org.apache.skywalking.apm.agent.core.plugin.interceptor.StaticMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassStaticMethodsEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;
import org.apache.skywalking.apm.agent.core.plugin.match.NameMatch;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.description.method.MethodDescription;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatcher;

/**
 * 增强 {@link org.apache.skywalking.apm.toolkit.trace.SWDebugUtils}:
 * <ul>
 *   <li>{@code enableDebug()}  -> {@code DynamicDebugRuntimeEnableInterceptor}</li>
 *   <li>{@code disableDebug()} -> {@code DynamicDebugRuntimeDisableInterceptor}</li>
 * </ul>
 * 两个方法都只匹配无参重载, 避免误拦同名其他签名。
 */
public class DynamicDebugRuntimeInstrumentation extends ClassStaticMethodsEnhancePluginDefine {

    private static final String ENHANCE_CLASS = "org.apache.skywalking.apm.toolkit.trace.SWDebugUtils";

    private static final String ENABLE_METHOD = "enableDebug";
    private static final String DISABLE_METHOD = "disableDebug";

    private static final String ENABLE_INTERCEPTOR = "org.apache.skywalking.apm.plugin.dynamicdebugruntime.v9.DynamicDebugRuntimeEnableInterceptor";
    private static final String DISABLE_INTERCEPTOR = "org.apache.skywalking.apm.plugin.dynamicdebugruntime.v9.DynamicDebugRuntimeDisableInterceptor";

    @Override
    protected ClassMatch enhanceClass() {
        return NameMatch.byName(ENHANCE_CLASS);
    }

    @Override
    public StaticMethodsInterceptPoint[] getStaticMethodsInterceptPoints() {
        return new StaticMethodsInterceptPoint[] {
            interceptPoint(ENABLE_METHOD, ENABLE_INTERCEPTOR),
            interceptPoint(DISABLE_METHOD, DISABLE_INTERCEPTOR)
        };
    }

    private static StaticMethodsInterceptPoint interceptPoint(final String methodName, final String interceptor) {
        return new StaticMethodsInterceptPoint() {
            @Override
            public ElementMatcher<MethodDescription> getMethodsMatcher() {
                return named(methodName).and(takesNoArguments());
            }

            @Override
            public String getMethodsInterceptor() {
                return interceptor;
            }

            @Override
            public boolean isOverrideArgs() {
                return false;
            }
        };
    }
}
