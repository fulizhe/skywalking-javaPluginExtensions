package org.apache.skywalking.apm.plugin.override.httpclient.v4.define;

import org.apache.skywalking.apm.agent.core.plugin.interceptor.StaticMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassStaticMethodsEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;
import org.apache.skywalking.apm.agent.core.plugin.match.NameMatch;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.description.method.MethodDescription;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatcher;

import java.util.Map;

import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.named;
import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

public class HttpClientParamRuntimeInstrumentation extends ClassStaticMethodsEnhancePluginDefine {
    private static final String ENHANCE_CLASS = "org.apache.skywalking.apm.toolkit.SWHttpClientCollectUtils";
    private static final String ENABLE_METHOD = "enableCollect";
    private static final String DISABLE_METHOD = "disableCollect";
    private static final String STATUS_METHOD = "statisticStatus";

    @Override
    protected ClassMatch enhanceClass() {
        return NameMatch.byName(ENHANCE_CLASS);
    }

    @Override
    public StaticMethodsInterceptPoint[] getStaticMethodsInterceptPoints() {
        return new StaticMethodsInterceptPoint[] {
            new StaticMethodsInterceptPoint() {
                @Override
                public ElementMatcher<MethodDescription> getMethodsMatcher() {
                    return named(ENABLE_METHOD).and(takesArgument(0, Map.class));
                }

                @Override
                public String getMethodsInterceptor() {
                    return "org.apache.skywalking.apm.plugin.override.httpclient.v4.HttpClientCollectEnableRuntimeInterceptor";
                }

                @Override
                public boolean isOverrideArgs() {
                    return false;
                }
            },
            new StaticMethodsInterceptPoint() {
                @Override
                public ElementMatcher<MethodDescription> getMethodsMatcher() {
                    return named(DISABLE_METHOD).and(takesArgument(0, Map.class));
                }

                @Override
                public String getMethodsInterceptor() {
                    return "org.apache.skywalking.apm.plugin.override.httpclient.v4.HttpClientCollectDisableRuntimeInterceptor";
                }

                @Override
                public boolean isOverrideArgs() {
                    return false;
                }
            },
            new StaticMethodsInterceptPoint() {
                @Override
                public ElementMatcher<MethodDescription> getMethodsMatcher() {
                    return named(STATUS_METHOD).and(takesNoArguments());
                }

                @Override
                public String getMethodsInterceptor() {
                    return "org.apache.skywalking.apm.plugin.override.httpclient.v4.HttpClientCollectStatusExposeInterceptor";
                }

                @Override
                public boolean isOverrideArgs() {
                    return false;
                }
            }
        };
    }
}
