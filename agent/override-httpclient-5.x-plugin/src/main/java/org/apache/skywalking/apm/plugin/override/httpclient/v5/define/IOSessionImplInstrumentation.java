package org.apache.skywalking.apm.plugin.override.httpclient.v5.define;

import org.apache.skywalking.apm.agent.core.plugin.interceptor.ConstructorInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.InstanceMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassInstanceMethodsEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.description.method.MethodDescription;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatcher;

import static org.apache.skywalking.apm.agent.core.plugin.match.NameMatch.byName;
import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.named;

// 与官方 apm-httpclient-5.x-plugin (v9.4.0) 的 IOSessionImplInstrumentation 同源, 未修改, 保持原样。
public class IOSessionImplInstrumentation extends ClassInstanceMethodsEnhancePluginDefine {

    private static final String ENHANCE_CLASS = "org.apache.hc.core5.reactor.IOSessionImpl";
    private static final String METHOD_NAME = "poll";
    private static final String INTERCEPT_CLASS =
        "org.apache.skywalking.apm.plugin.override.httpclient.v5.IOSessionImplPollInterceptor";

    @Override
    protected ClassMatch enhanceClass() {
        return byName(ENHANCE_CLASS);
    }

    @Override
    public ConstructorInterceptPoint[] getConstructorsInterceptPoints() {
        return null;
    }

    @Override
    public InstanceMethodsInterceptPoint[] getInstanceMethodsInterceptPoints() {
        return new InstanceMethodsInterceptPoint[]{
                new InstanceMethodsInterceptPoint() {
                    @Override
                    public ElementMatcher<MethodDescription> getMethodsMatcher() {
                        return named(METHOD_NAME);
                    }

                    @Override
                    public String getMethodsInterceptor() {
                        return INTERCEPT_CLASS;
                    }

                    @Override
                    public boolean isOverrideArgs() {
                        return false;
                    }
                }
        };
    }
}
