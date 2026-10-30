package org.apache.skywalking.apm.plugin.override.httpclient.v5;

import org.apache.hc.client5.http.routing.RoutingSupport;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

// SEE https://github.com/apache/skywalking-java/blob/v9.4.0/apm-sniffer/apm-sdk-plugin/httpclient-5.x-plugin/src/main/java/org/apache/skywalking/apm/plugin/httpclient/v5/HttpClientDoExecuteInterceptor.java
public class HttpClientExecuteInterceptor implements InstanceMethodsAroundInterceptor {
    private static final String ERROR_URI = "/_blank";
    private static final ILog LOGGER = LogManager.getLogger(HttpClientExecuteInterceptor.class);

    @Override
    public void beforeMethod(final EnhancedInstance objInst,
                             final Method method,
                             final Object[] allArguments,
                             final Class<?>[] argumentsTypes,
                             final MethodInterceptResult result) throws Throwable {
        if (allArguments == null || allArguments.length < 2 || allArguments[1] == null) {
            return;
        }

        final ClassicHttpRequest httpRequest = (ClassicHttpRequest) allArguments[1];
        final HttpHost httpHost = resolveHttpHost(allArguments);
        if (httpHost == null) {
            return;
        }

        final ContextCarrier contextCarrier = new ContextCarrier();
        final String remotePeer = httpHost.getHostName() + ":" + port(httpHost);
        final String requestUrl = requestUrl(httpRequest);
        final String requestUri = getRequestURI(requestUrl);
        final AbstractSpan span = ContextManager.createExitSpan(requestUri, contextCarrier, remotePeer);
        final boolean officialCollectEnabled = HttpClientCollectionSwitch.isOfficialCollectEnabled();
        final boolean overrideCollectEnabled = HttpClientCollectionSwitch.isOverrideCollectEnabled();

        if (ERROR_URI.equals(requestUri)) {
            span.errorOccurred();
            LOGGER.warn("### Failed to resolve request URI for httpclient5 request, requestUrl={}", requestUrl);
        }

        span.setComponent(ComponentsDefine.HTTPCLIENT);
        Tags.URL.set(span, buildSpanValue(httpHost, requestUrl));
        Tags.HTTP.METHOD.set(span, httpRequest.getMethod());
        SpanLayer.asHttp(span);

        CarrierItem next = contextCarrier.items();
        while (next.hasNext()) {
            next = next.next();
            httpRequest.setHeader(next.getHeadKey(), next.getHeadValue());
        }

        if (LOGGER.isDebugEnable()) {
            LOGGER.debug("### Intercept httpclient5 request, method={}, uri={}, remotePeer={}, officialEnabled={}, overrideEnabled={}",
                httpRequest.getMethod(), requestUri, remotePeer, officialCollectEnabled,
                overrideCollectEnabled);
        }

        if (officialCollectEnabled || overrideCollectEnabled) {
            collectHttpParam(httpRequest, span);
        }
    }

    @Override
    public Object afterMethod(final EnhancedInstance objInst,
                              final Method method,
                              final Object[] allArguments,
                              final Class<?>[] argumentsTypes,
                              final Object ret) throws Throwable {
        if (allArguments == null || allArguments.length < 2 || allArguments[1] == null) {
            return ret;
        }
        // 与 beforeMethod 对称: 5.x 的 doExecute 首个 HttpHost 参数可能为 null,
        // 若无法从请求推导出 host, 则 beforeMethod 未创建 span, 这里也不能 stopSpan。
        if (resolveHttpHost(allArguments) == null) {
            return ret;
        }

        if (ret != null) {
            final ClassicHttpResponse response = (ClassicHttpResponse) ret;
            final ClassicHttpRequest httpRequest = (ClassicHttpRequest) allArguments[1];
            final boolean officialCollectEnabled = HttpClientCollectionSwitch.isOfficialCollectEnabled();
            final boolean overrideCollectEnabled = HttpClientCollectionSwitch.isOverrideCollectEnabled();
            final int statusCode = response.getCode();
            final AbstractSpan span = ContextManager.activeSpan();
            Tags.HTTP_RESPONSE_STATUS_CODE.set(span, statusCode);
            if (statusCode >= 400) {
                span.errorOccurred();
                LOGGER.warn("### Httpclient5 request finished with error status, method={}, uri={}, statusCode={}",
                    httpRequest.getMethod(), requestUrl(httpRequest), statusCode);
            } else if (LOGGER.isDebugEnable()) {
                LOGGER.debug("### Httpclient5 request finished, method={}, uri={}, statusCode={}",
                    httpRequest.getMethod(), requestUrl(httpRequest), statusCode);
            }
            if (!officialCollectEnabled && !overrideCollectEnabled && span.isProfiling()) {
                LOGGER.info("### Collect httpclient5 params by profiling fallback, method={}, uri={}",
                    httpRequest.getMethod(), requestUrl(httpRequest));
                collectHttpParam(httpRequest, span);
            }
            collectHttpResponse(response, span, httpRequest);
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
        if (allArguments == null || allArguments.length < 2 || allArguments[1] == null) {
            return;
        }
        if (resolveHttpHost(allArguments) == null) {
            return;
        }
        LOGGER.error(t, "### Httpclient5 interceptor failed, method={}, request={}",
            method == null ? null : method.getName(),
            allArguments != null && allArguments.length > 1 ? allArguments[1] : null);
        ContextManager.activeSpan().log(t);
    }

    private HttpHost resolveHttpHost(final Object[] allArguments) {
        // 与官方 InternalClientDoExecuteInterceptor 一致: 5.x 的 doExecute 首个 HttpHost 参数
        // 在相对 URI 场景下可能为 null, 此时从请求推导; 推导失败则不追踪(与官方 skipIntercept 等价)。
        final HttpHost httpHost = (HttpHost) allArguments[0];
        if (httpHost != null) {
            return httpHost;
        }
        try {
            return RoutingSupport.determineHost((HttpRequest) allArguments[1]);
        } catch (Exception ignore) {
            return null;
        }
    }

    private String requestUrl(final ClassicHttpRequest httpRequest) {
        try {
            final URI uri = httpRequest.getUri();
            return uri == null ? httpRequest.getRequestUri() : uri.toString();
        } catch (Exception ignored) {
            return httpRequest.getRequestUri();
        }
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

    private void collectHttpParam(final ClassicHttpRequest httpRequest, final AbstractSpan span) {
        final HttpClientParamCollector.CollectedTags collectedTags = HttpClientParamCollector.collect(httpRequest);
        if (collectedTags.getParams() != null) {
            Tags.HTTP.PARAMS.set(span, collectedTags.getParams());
            span.tag(Tags.ofKey(HttpClientParamCollector.TAG_KEY_HTTP_PARAMS), collectedTags.getParams());
        }
        if (collectedTags.getFiles() != null) {
            span.tag(Tags.ofKey(HttpClientParamCollector.TAG_KEY_HTTP_FILES), collectedTags.getFiles());
        }
        if (LOGGER.isDebugEnable()) {
            LOGGER.debug("### Collected httpclient5 request tags, method={}, uri={}, paramsLength={}, filesLength={}",
                httpRequest.getMethod(), requestUrl(httpRequest),
                lengthOf(collectedTags.getParams()), lengthOf(collectedTags.getFiles()));
        }
    }

    private int lengthOf(final String value) {
        return value == null ? 0 : value.length();
    }

    private void collectHttpResponse(final ClassicHttpResponse response,
                                     final AbstractSpan span,
                                     final ClassicHttpRequest httpRequest) {
        final String responseBody = HttpClientResponseCollector.collect(response);
        if (responseBody == null) {
            return;
        }
        span.tag(Tags.ofKey(HttpClientResponseCollector.TAG_KEY_HTTP_RESPONSE_BODY), responseBody);
        if (LOGGER.isDebugEnable()) {
            LOGGER.debug("### Collected httpclient5 response body, method={}, uri={}, responseLength={}",
                httpRequest.getMethod(), requestUrl(httpRequest), responseBody.length());
        }
    }
}
