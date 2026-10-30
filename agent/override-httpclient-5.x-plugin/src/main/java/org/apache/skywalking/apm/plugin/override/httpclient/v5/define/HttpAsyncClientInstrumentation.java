package org.apache.skywalking.apm.plugin.override.httpclient.v5.define;

import org.apache.skywalking.apm.agent.core.plugin.interceptor.ConstructorInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.InstanceMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassInstanceMethodsEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;
import org.apache.skywalking.apm.agent.core.plugin.match.MultiClassNameMatch;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.description.method.MethodDescription;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatcher;

import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.named;

// 与官方 apm-httpclient-5.x-plugin (v9.4.0) 的 HttpAsyncClientInstrumentation 同源, 未修改, 保持原样。
public class HttpAsyncClientInstrumentation extends ClassInstanceMethodsEnhancePluginDefine {

    private static final String ENHANCE_CLASS_MINIMAL_HTTP = "org.apache.hc.client5.http.impl.async.MinimalHttpAsyncClient";
    private static final String ENHANCE_CLASS_MINIMAL_H2 = "org.apache.hc.client5.http.impl.async.MinimalH2AsyncClient";
    private static final String ENHANCE_CLASS_INTERNAL_HTTP = "org.apache.hc.client5.http.impl.async.InternalHttpAsyncClient";
    private static final String ENHANCE_CLASS_INTERNAL_H2 = "org.apache.hc.client5.http.impl.async.InternalH2AsyncClient";
    private static final String METHOD_NAME = "doExecute";
    private static final String INTERCEPT_CLASS =
        "org.apache.skywalking.apm.plugin.override.httpclient.v5.HttpAsyncClientDoExecuteInterceptor";

    @Override
    public ClassMatch enhanceClass() {
        return MultiClassNameMatch.byMultiClassMatch(
                ENHANCE_CLASS_MINIMAL_HTTP,
                ENHANCE_CLASS_MINIMAL_H2,
                ENHANCE_CLASS_INTERNAL_HTTP,
                ENHANCE_CLASS_INTERNAL_H2);
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
                        return true;
                    }
                }
        };
    }
}
