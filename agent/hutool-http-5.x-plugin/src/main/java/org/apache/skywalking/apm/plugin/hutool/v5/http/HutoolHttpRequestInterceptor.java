/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.skywalking.apm.plugin.hutool.v5.http;

import cn.hutool.core.util.URLUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
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
import java.net.URI;

public class HutoolHttpRequestInterceptor implements InstanceMethodsAroundInterceptor {
    private static final ILog LOGGER = LogManager.getLogger(HutoolHttpRequestInterceptor.class);

    @Override
    public void beforeMethod(final EnhancedInstance objInst,
                             final Method method,
                             final Object[] allArguments,
                             final Class<?>[] argumentsTypes,
                             final MethodInterceptResult result) throws Throwable {
        if (!(objInst instanceof HttpRequest)) {
            return;
        }

        final HttpRequest request = (HttpRequest) objInst;
        final ContextCarrier contextCarrier = new ContextCarrier();
        final URI uri = URLUtil.toURI(request.getUrl());
        final String requestUri = getRequestURI(uri);
        final String remotePeer = buildRemotePeer(uri);
        final boolean officialCollectEnabled = HutoolHttpCollectionSwitch.isOfficialCollectEnabled();
        final boolean overrideCollectEnabled = HutoolHttpCollectionSwitch.isOverrideCollectEnabled();
        final AbstractSpan span = ContextManager.createExitSpan(requestUri, contextCarrier, remotePeer);

        span.setComponent(ComponentsDefine.HUTOOL);
        Tags.URL.set(span, request.getUrl());
        Tags.HTTP.METHOD.set(span, request.getMethod().name());
        SpanLayer.asHttp(span);

        CarrierItem next = contextCarrier.items();
        while (next.hasNext()) {
            next = next.next();
            request.header(next.getHeadKey(), next.getHeadValue());
        }

        if (LOGGER.isDebugEnable()) {
            LOGGER.debug(
                "### Intercept hutool http request, method={}, uri={}, remotePeer={}, officialEnabled={}, overrideEnabled={}",
                request.getMethod().name(), requestUri, remotePeer, officialCollectEnabled, overrideCollectEnabled);
        }

        if (officialCollectEnabled || overrideCollectEnabled) {
        	// 根据配置项采集不同类型数据, 在这个方法里执行, 其中的 HutoolHttpParamCollector.collect（...）
            collectHttpParam(request, span);
        }
    }

    @Override
    public Object afterMethod(final EnhancedInstance objInst,
                              final Method method,
                              final Object[] allArguments,
                              final Class<?>[] argumentsTypes,
                              final Object ret) throws Throwable {
        if (ret instanceof HttpResponse) {
            final HttpResponse response = (HttpResponse) ret;
            final AbstractSpan span = ContextManager.activeSpan();
            final int statusCode = response.getStatus();
            Tags.HTTP_RESPONSE_STATUS_CODE.set(span, statusCode);

            if (statusCode >= 400) {
                span.errorOccurred();
                if (objInst instanceof HttpRequest) {
                    final HttpRequest request = (HttpRequest) objInst;
                    LOGGER.warn("### Hutool http request finished with error status, method={}, uri={}, statusCode={}",
                        request.getMethod().name(), request.getUrl(), statusCode);
                }
            } else if (LOGGER.isDebugEnable() && objInst instanceof HttpRequest) {
                final HttpRequest request = (HttpRequest) objInst;
                LOGGER.debug("### Hutool http request finished, method={}, uri={}, statusCode={}",
                    request.getMethod().name(), request.getUrl(), statusCode);
            }

            if (objInst instanceof HttpRequest
                && !HutoolHttpCollectionSwitch.isOfficialCollectEnabled()
                && !HutoolHttpCollectionSwitch.isOverrideCollectEnabled()
                && span.isProfiling()) {
                final HttpRequest request = (HttpRequest) objInst;
                LOGGER.info("### Collect hutool http params by profiling fallback, method={}, uri={}",
                    request.getMethod().name(), request.getUrl());
                collectHttpParam(request, span);
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
        LOGGER.error(t, "### Hutool http interceptor failed, method={}, requestUrl={}",
            method == null ? null : method.getName(),
            objInst instanceof HttpRequest ? ((HttpRequest) objInst).getUrl() : null);
        ContextManager.activeSpan().log(t);
    }

    private String getRequestURI(final URI uri) {
        if (uri == null || uri.getPath() == null || uri.getPath().length() == 0) {
            return "/";
        }
        return uri.getPath();
    }

    private String buildRemotePeer(final URI uri) {
        if (uri == null) {
            return "UNKNOWN";
        }
        return uri.getHost() + ":" + port(uri);
    }

    private int port(final URI uri) {
        if (uri == null) {
            return -1;
        }
        final int port = uri.getPort();
        if (port > 0) {
            return port;
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void collectHttpParam(final HttpRequest request, final AbstractSpan span) {
        final HutoolHttpParamCollector.CollectedTags collectedTags = HutoolHttpParamCollector.collect(request);
        if (collectedTags.getParams() != null) {
            Tags.HTTP.PARAMS.set(span, collectedTags.getParams());
            span.tag(Tags.ofKey(HutoolHttpParamCollector.TAG_KEY_HTTP_PARAMS), collectedTags.getParams());
        }
        if (collectedTags.getFiles() != null) {
            span.tag(Tags.ofKey(HutoolHttpParamCollector.TAG_KEY_HTTP_FILES), collectedTags.getFiles());
        }
        if (LOGGER.isDebugEnable()) {
            LOGGER.debug("### Collected hutool http request tags, method={}, uri={}, paramsLength={}, filesLength={}",
                request.getMethod().name(), request.getUrl(), lengthOf(collectedTags.getParams()),
                lengthOf(collectedTags.getFiles()));
        }
    }

    private int lengthOf(final String value) {
        return value == null ? 0 : value.length();
    }
}
