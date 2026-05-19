package org.apache.skywalking.apm.plugin.override.httpclient.v4;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.RequestLine;
import org.apache.http.StatusLine;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;
import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;

// SEE https://github.com/apache/skywalking-java/blob/e0e8b3c8c304735991e057d431910ed1f4a57cdd/apm-sniffer/apm-sdk-plugin/httpClient-4.x-plugin/src/main/java/org/apache/skywalking/apm/plugin/httpClient/v4/HttpClientExecuteInterceptor.java
public class HttpClientExecuteInterceptor implements InstanceMethodsAroundInterceptor {
    private static final String ERROR_URI = "/_blank";

    @Override
    public void beforeMethod(final EnhancedInstance objInst,
                             final Method method,
                             final Object[] allArguments,
                             final Class<?>[] argumentsTypes,
                             final MethodInterceptResult result) throws Throwable {
        if (allArguments[0] == null || allArguments[1] == null) {
            return;
        }

        final HttpHost httpHost = (HttpHost) allArguments[0];
        final HttpRequest httpRequest = (HttpRequest) allArguments[1];
        final ContextCarrier contextCarrier = new ContextCarrier();
        final String remotePeer = httpHost.getHostName() + ":" + port(httpHost);
        final String requestUrl = httpRequest.getRequestLine().getUri();
        final String requestUri = getRequestURI(requestUrl);
        final AbstractSpan span = ContextManager.createExitSpan(requestUri, contextCarrier, remotePeer);

        if (ERROR_URI.equals(requestUri)) {
            span.errorOccurred();
        }

        span.setComponent(ComponentsDefine.HTTPCLIENT);
        Tags.URL.set(span, buildSpanValue(httpHost, requestUrl));
        Tags.HTTP.METHOD.set(span, httpRequest.getRequestLine().getMethod());
        SpanLayer.asHttp(span);

        CarrierItem next = contextCarrier.items();
        while (next.hasNext()) {
            next = next.next();
            httpRequest.setHeader(next.getHeadKey(), next.getHeadValue());
        }

        if (HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS) {
            collectHttpParam(httpRequest, span);
        }
    }

    @Override
    public Object afterMethod(final EnhancedInstance objInst,
                              final Method method,
                              final Object[] allArguments,
                              final Class<?>[] argumentsTypes,
                              final Object ret) throws Throwable {
        if (allArguments[0] == null || allArguments[1] == null) {
            return ret;
        }

        if (ret != null) {
            final HttpResponse response = (HttpResponse) ret;
            final StatusLine statusLine = response.getStatusLine();
            if (statusLine != null) {
                final int statusCode = statusLine.getStatusCode();
                final AbstractSpan span = ContextManager.activeSpan();
                Tags.HTTP_RESPONSE_STATUS_CODE.set(span, statusCode);
                if (statusCode >= 400) {
                    span.errorOccurred();
                }
                final HttpRequest httpRequest = (HttpRequest) allArguments[1];
                if (!HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS && span.isProfiling()) {
                    collectHttpParam(httpRequest, span);
                }
            }
        }

        ContextManager.stopSpan();
        return ret;
    }

    @Override
    public void handleMethodException(final EnhancedInstance objInst,
                                      final Method method,
                                      final Object[] allArguments,
                                      final Class<?>[] argumentsTypes,
                                      final Throwable t) {
        ContextManager.activeSpan().log(t);
    }

    private String getRequestURI(final String uri) {
        if (isUrl(uri)) {
            try {
                final String path = new URL(uri).getPath();
                return path != null && path.length() > 0 ? path : "/";
            } catch (MalformedURLException ignored) {
                return ERROR_URI;
            }
        }
        return uri;
    }

    private boolean isUrl(final String uri) {
        final String lowerCaseUrl = uri.toLowerCase();
        return lowerCaseUrl.startsWith("http") || lowerCaseUrl.startsWith("https");
    }

    private String buildSpanValue(final HttpHost httpHost, final String uri) {
        if (isUrl(uri)) {
            return uri;
        }
        return new StringBuilder()
            .append(httpHost.getSchemeName().toLowerCase())
            .append("://")
            .append(httpHost.getHostName())
            .append(':')
            .append(port(httpHost))
            .append(uri)
            .toString();
    }

    private int port(final HttpHost httpHost) {
        final int port = httpHost.getPort();
        if (port > 0) {
            return port;
        }
        return "https".equals(httpHost.getSchemeName().toLowerCase()) ? 443 : 80;
    }

    private void collectHttpParam(final HttpRequest httpRequest, final AbstractSpan span) {
        final HttpClientParamCollector.CollectedTags collectedTags = HttpClientParamCollector.collect(httpRequest);
        if (collectedTags.getParams() != null) {
            Tags.HTTP.PARAMS.set(span, collectedTags.getParams());
            span.tag(Tags.ofKey(HttpClientParamCollector.TAG_KEY_HTTP_PARAMS), collectedTags.getParams());
        }
        if (collectedTags.getFiles() != null) {
            span.tag(Tags.ofKey(HttpClientParamCollector.TAG_KEY_HTTP_FILES), collectedTags.getFiles());
        }
    }
}
