package org.apache.skywalking.apm.plugin.override.httpclient.v4.define;

import org.apache.skywalking.apm.agent.core.plugin.interceptor.InstanceMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.description.method.MethodDescription;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatcher;

import static org.apache.skywalking.apm.agent.core.plugin.match.NameMatch.byName;
import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.named;

public class DefaultRequestDirectorInstrumentation extends HttpClientInstrumentation {
    @Override
    protected ClassMatch enhanceClass() {
        return byName("org.apache.http.impl.client.DefaultRequestDirector");
    }

    @Override
    public InstanceMethodsInterceptPoint[] getInstanceMethodsInterceptPoints() {
        return new InstanceMethodsInterceptPoint[] {
            new InstanceMethodsInterceptPoint() {
                @Override
                public ElementMatcher<MethodDescription> getMethodsMatcher() {
                    return named("execute");
                }

                @Override
                public String getMethodsInterceptor() {
                    return getInstanceMethodsInterceptor();
                }

                @Override
                public boolean isOverrideArgs() {
                    return false;
                }
            }
        };
    }
}
