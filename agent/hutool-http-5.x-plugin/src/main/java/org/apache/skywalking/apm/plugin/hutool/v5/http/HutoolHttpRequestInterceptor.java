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

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;

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
import org.apache.skywalking.apm.network.trace.component.OfficialComponent;

import cn.hutool.core.util.URLUtil;
import cn.hutool.http.HttpResponse;

/**
 * {@link HutoolHttpRequestInterceptor} intercepted the method of hutool-http.
 * <p> 参考 apm-httpClient-4.x-plugin 中的 {@code HttpClientExecuteInterceptor}
 */
public class HutoolHttpRequestInterceptor implements InstanceMethodsAroundInterceptor {
	private static final ILog LOGGER = LogManager.getLogger(HutoolHttpRequestInterceptor.class);

	@Override
	public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
			MethodInterceptResult result) throws Throwable {
		if (!(objInst instanceof cn.hutool.http.HttpRequest)) {
			return;
		}

		final cn.hutool.http.HttpRequest request = (cn.hutool.http.HttpRequest) objInst;
		
		final ContextCarrier contextCarrier = new ContextCarrier();
		final URI uri = URLUtil.toURI(request.getUrl());
		final AbstractSpan span = ContextManager.createExitSpan("hutool/http/" + method.getName(), contextCarrier,
				uri.getHost() + ":" + uri.getPort());
		span.setComponent(new OfficialComponent(9999, "hutoolHttp"));

		Tags.URL.set(span, request.getUrl());
		Tags.HTTP.METHOD.set(span, request.getMethod().name());
		Tags.HTTP.HEADERS.set(span, "charset=" + request.charset());
		SpanLayer.asHttp(span);

		CarrierItem next = contextCarrier.items();
		while (next.hasNext()) {
			next = next.next();
			request.header(next.getHeadKey(), next.getHeadValue());
		}

	}

	@Override
	public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
			Object ret) throws Throwable {
		if (ret != null && ret instanceof HttpResponse) {
			final HttpResponse response = (HttpResponse) ret;

			int statusCode = response.getStatus();
			AbstractSpan span = ContextManager.activeSpan();
			if (statusCode >= 400) {
				span.errorOccurred();
				Tags.HTTP_RESPONSE_STATUS_CODE.set(span, statusCode);
			}
		}

		ContextManager.stopSpan();
		return ret;
	}

	@Override
	public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
			Class<?>[] argumentsTypes, Throwable t) {
		ContextManager.activeSpan().errorOccurred().log(t);
	}
}
