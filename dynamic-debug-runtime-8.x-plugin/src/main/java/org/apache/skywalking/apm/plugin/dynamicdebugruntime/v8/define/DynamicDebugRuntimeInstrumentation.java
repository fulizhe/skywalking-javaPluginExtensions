package org.apache.skywalking.apm.plugin.dynamicdebugruntime.v8.define;

import org.apache.skywalking.apm.agent.core.plugin.interceptor.StaticMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassStaticMethodsEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;
import org.apache.skywalking.apm.agent.core.plugin.match.NameMatch;

import org.apache.skywalking.apm.dependencies.net.bytebuddy.description.method.MethodDescription;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatcher;

import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.named;
import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.takesNoArguments;
/**
 * Refer To {@code TraceContextActivation}
 * <p>
 */
public class DynamicDebugRuntimeInstrumentation extends ClassStaticMethodsEnhancePluginDefine {

    private static final String ENHANCE_CLASS = "org.apache.skywalking.apm.toolkit.trace.SWUtils";
    private static final String ENHANCE_METHOD = "toggleDebug";
    private static final String INTERCEPTOR_CLASS = "org.apache.skywalking.apm.plugin.dynamicdebugruntime.v8.DynamicDebugRuntimeInterceptor";

    /**
     * @return the target class, which needs active.
     */
    @Override
    protected ClassMatch enhanceClass() {
        return NameMatch.byName(ENHANCE_CLASS);
    }

    /**
     * @return the collection of {@link StaticMethodsInterceptPoint}, represent the intercepted methods and their
     * interceptors.
     */
    @Override
    public StaticMethodsInterceptPoint[] getStaticMethodsInterceptPoints() {
        return new StaticMethodsInterceptPoint[] {
            new StaticMethodsInterceptPoint() {
                @Override
                public ElementMatcher<MethodDescription> getMethodsMatcher() {
                    return named(ENHANCE_METHOD).and(takesNoArguments());
                }

                @Override
                public String getMethodsInterceptor() {
                    return INTERCEPTOR_CLASS;
                }

                @Override
                public boolean isOverrideArgs() {
                    return false;
                }
            }
        };
    }
}
