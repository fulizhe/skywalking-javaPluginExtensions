package org.apache.skywalking.apm.plugin.hutool.v5.http;
//
//public class HttpClientExecuteInterceptor implements InstanceMethodsAroundInterceptor {
//
//    @Override
//    public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
//                             MethodInterceptResult result) throws Throwable {
//        if (allArguments[0] == null || allArguments[1] == null) {
//            // illegal args, can't trace. ignore.
//            return;
//        }
//        final HttpHost httpHost = (HttpHost) allArguments[0];
//        HttpRequest httpRequest = (HttpRequest) allArguments[1];
//        final ContextCarrier contextCarrier = new ContextCarrier();
//
//        String remotePeer = httpHost.getHostName() + ":" + port(httpHost);
//
//        String uri = httpRequest.getRequestLine().getUri();
//        String requestURI = getRequestURI(uri);
//        String operationName = requestURI;
//        AbstractSpan span = ContextManager.createExitSpan(operationName, contextCarrier, remotePeer);
//
//        span.setComponent(ComponentsDefine.HTTPCLIENT);
//        Tags.URL.set(span, buildSpanValue(httpHost, uri));
//        Tags.HTTP.METHOD.set(span, httpRequest.getRequestLine().getMethod());
//        SpanLayer.asHttp(span);
//
//        CarrierItem next = contextCarrier.items();
//        while (next.hasNext()) {
//            next = next.next();
//            httpRequest.setHeader(next.getHeadKey(), next.getHeadValue());
//        }
//        if (HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS) {
//            collectHttpParam(httpRequest, span);
//        }
//    }
//
//    @Override
//    public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
//                              Object ret) throws Throwable {
//        if (allArguments[0] == null || allArguments[1] == null) {
//            return ret;
//        }
//
//        if (ret != null) {
//            HttpResponse response = (HttpResponse) ret;
//            StatusLine responseStatusLine = response.getStatusLine();
//            if (responseStatusLine != null) {
//                int statusCode = responseStatusLine.getStatusCode();
//                AbstractSpan span = ContextManager.activeSpan();
//                if (statusCode >= 400) {
//                    span.errorOccurred();
//                    Tags.HTTP_RESPONSE_STATUS_CODE.set(span, statusCode);
//                }
//                HttpRequest httpRequest = (HttpRequest) allArguments[1];
//                // Active HTTP parameter collection automatically in the profiling context.
//                if (!HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS && span.isProfiling()) {
//                    collectHttpParam(httpRequest, span);
//                }
//            }
//        }
//
//        ContextManager.stopSpan();
//        return ret;
//    }
//
//    @Override
//    public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
//                                      Class<?>[] argumentsTypes, Throwable t) {
//        AbstractSpan activeSpan = ContextManager.activeSpan();
//        activeSpan.log(t);
//    }
//
//    private String getRequestURI(String uri) throws MalformedURLException {
//        if (isUrl(uri)) {
//            String requestPath = new URL(uri).getPath();
//            return requestPath != null && requestPath.length() > 0 ? requestPath : "/";
//        } else {
//            return uri;
//        }
//    }
//
//    private boolean isUrl(String uri) {
//        String lowerUrl = uri.toLowerCase();
//        return lowerUrl.startsWith("http") || lowerUrl.startsWith("https");
//    }
//
//    private String buildSpanValue(HttpHost httpHost, String uri) {
//        if (isUrl(uri)) {
//            return uri;
//        } else {
//            StringBuilder buff = new StringBuilder();
//            buff.append(httpHost.getSchemeName().toLowerCase());
//            buff.append("://");
//            buff.append(httpHost.getHostName());
//            buff.append(":");
//            buff.append(port(httpHost));
//            buff.append(uri);
//            return buff.toString();
//        }
//    }
//
//    private int port(HttpHost httpHost) {
//        int port = httpHost.getPort();
//        return port > 0 ? port : "https".equals(httpHost.getSchemeName().toLowerCase()) ? 443 : 80;
//    }
//
//    private void collectHttpParam(HttpRequest httpRequest, AbstractSpan span) {
//        if (httpRequest instanceof HttpUriRequest) {
//            URI uri = ((HttpUriRequest) httpRequest).getURI();
//            String tagValue = uri.getQuery();
//            if (StringUtil.isNotEmpty(tagValue)) {
//                tagValue = HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD > 0 ?
//                        StringUtil.cut(tagValue, HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD) :
//                        tagValue;
//                Tags.HTTP.PARAMS.set(span, tagValue);
//            }
//        }
//    }
//}
