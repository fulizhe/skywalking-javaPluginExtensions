package org.apache.skywalking.apm.plugin.override.httpclient.v4.define;

import org.apache.skywalking.apm.agent.core.plugin.interceptor.ConstructorInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassInstanceMethodsEnhancePluginDefine;

public abstract class HttpClientInstrumentation extends ClassInstanceMethodsEnhancePluginDefine {
    private static final String INTERCEPTOR_CLASS =
        "org.apache.skywalking.apm.plugin.override.httpclient.v4.HttpClientExecuteInterceptor";

    @Override
    public ConstructorInterceptPoint[] getConstructorsInterceptPoints() {
        return null;
    }

    protected String getInstanceMethodsInterceptor() {
        return INTERCEPTOR_CLASS;
    }
}
