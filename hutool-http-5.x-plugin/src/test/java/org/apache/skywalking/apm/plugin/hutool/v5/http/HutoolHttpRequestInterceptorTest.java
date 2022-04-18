/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.plugin.hutool.v5.http;

import static junit.framework.TestCase.assertNotNull;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;

import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractTracingSpan;
import org.apache.skywalking.apm.agent.core.context.trace.LogDataEntity;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.context.util.TagValuePair;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.test.helper.SegmentHelper;
import org.apache.skywalking.apm.agent.test.helper.SpanHelper;
import org.apache.skywalking.apm.agent.test.tools.AgentServiceRule;
import org.apache.skywalking.apm.agent.test.tools.SegmentStorage;
import org.apache.skywalking.apm.agent.test.tools.SegmentStoragePoint;
import org.apache.skywalking.apm.agent.test.tools.TracingSegmentRunner;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(TracingSegmentRunner.class)
@PrepareForTest(HttpRequest.class)
public class HutoolHttpRequestInterceptorTest {

	@SegmentStoragePoint
	private SegmentStorage segmentStorage;

	@Rule
	public AgentServiceRule agentServiceRule = new AgentServiceRule();

	private HutoolHttpRequestInterceptor hutoolHttpRequestInterceptor;
	@Mock
	private HttpRequest httpRequest;

	@Mock
	private HttpResponse httpResponse;

	private Object[] allArguments;
	private Class[] argumentsType;

	@Mock
	private EnhancedInstance enhancedInstance;

	@Before
	public void setUp() throws Exception {

		ServiceManager.INSTANCE.boot();
		hutoolHttpRequestInterceptor = new HutoolHttpRequestInterceptor();

		PowerMockito.mock(HttpRequest.class);
		when(httpRequest.getUrl()).thenReturn("http://127.0.0.1:8080/test-web/test?a=1&b=test");
		when(httpResponse.getStatus()).thenReturn(200);

		
		allArguments = new Object[] { true };
		argumentsType = new Class[] { boolean.class };
	}

	@Test
	public void testHttpClient() throws Throwable {
		hutoolHttpRequestInterceptor.beforeMethod(enhancedInstance, null, allArguments, argumentsType, null);
		hutoolHttpRequestInterceptor.afterMethod(enhancedInstance, null, allArguments, argumentsType, httpResponse);

		Assert.assertThat(segmentStorage.getTraceSegments().size(), is(1));
		TraceSegment traceSegment = segmentStorage.getTraceSegments().get(0);

		List<AbstractTracingSpan> spans = SegmentHelper.getSpans(traceSegment);
		assertHttpSpan(spans.get(0));
		verify(httpRequest, times(3)).header(anyString(), anyString());
	}
//
//	@Test
//	public void testStatusCodeNotEquals200() throws Throwable {
//		when(statusLine.getStatusCode()).thenReturn(500);
//		httpClientExecuteInterceptor.beforeMethod(enhancedInstance, null, allArguments, argumentsType, null);
//		httpClientExecuteInterceptor.afterMethod(enhancedInstance, null, allArguments, argumentsType, httpResponse);
//
//		Assert.assertThat(segmentStorage.getTraceSegments().size(), is(1));
//		TraceSegment traceSegment = segmentStorage.getTraceSegments().get(0);
//		List<AbstractTracingSpan> spans = SegmentHelper.getSpans(traceSegment);
//
//		assertThat(spans.size(), is(1));
//
//		List<TagValuePair> tags = SpanHelper.getTags(spans.get(0));
//		assertThat(tags.size(), is(4));
//		assertThat(tags.get(3).getValue(), is("500"));
//
//		assertHttpSpan(spans.get(0));
//		assertThat(SpanHelper.getErrorOccurred(spans.get(0)), is(true));
//		verify(request, times(3)).setHeader(anyString(), anyString());
//	}
//
//	@Test
//	public void testHttpClientWithException() throws Throwable {
//		httpClientExecuteInterceptor.beforeMethod(enhancedInstance, null, allArguments, argumentsType, null);
//		httpClientExecuteInterceptor.handleMethodException(enhancedInstance, null, allArguments, argumentsType,
//				new RuntimeException("testException"));
//		httpClientExecuteInterceptor.afterMethod(enhancedInstance, null, allArguments, argumentsType, httpResponse);
//
//		Assert.assertThat(segmentStorage.getTraceSegments().size(), is(1));
//		TraceSegment traceSegment = segmentStorage.getTraceSegments().get(0);
//		List<AbstractTracingSpan> spans = SegmentHelper.getSpans(traceSegment);
//
//		assertThat(spans.size(), is(1));
//		AbstractTracingSpan span = spans.get(0);
//		assertHttpSpan(span);
//		assertThat(SpanHelper.getErrorOccurred(span), is(true));
//		assertHttpSpanErrorLog(SpanHelper.getLogs(span));
//		verify(request, times(3)).setHeader(anyString(), anyString());
//
//	}
//
//	@Test
//	public void testUriNotProtocol() throws Throwable {
//		when(request.getRequestLine()).thenReturn(new RequestLine() {
//			@Override
//			public String getMethod() {
//				return "GET";
//			}
//
//			@Override
//			public ProtocolVersion getProtocolVersion() {
//				return new ProtocolVersion("http", 1, 1);
//			}
//
//			@Override
//			public String getUri() {
//				return "/test-web/test";
//			}
//		});
//		httpClientExecuteInterceptor.beforeMethod(enhancedInstance, null, allArguments, argumentsType, null);
//		httpClientExecuteInterceptor.afterMethod(enhancedInstance, null, allArguments, argumentsType, httpResponse);
//
//		Assert.assertThat(segmentStorage.getTraceSegments().size(), is(1));
//		TraceSegment traceSegment = segmentStorage.getTraceSegments().get(0);
//
//		List<AbstractTracingSpan> spans = SegmentHelper.getSpans(traceSegment);
//		assertHttpSpan(spans.get(0));
//		verify(request, times(3)).setHeader(anyString(), anyString());
//	}

	private void assertHttpSpanErrorLog(List<LogDataEntity> logs) {
		assertThat(logs.size(), is(1));
		LogDataEntity logData = logs.get(0);
		Assert.assertThat(logData.getLogs().size(), is(4));
		Assert.assertThat(logData.getLogs().get(0).getValue(), CoreMatchers.<Object>is("error"));
		Assert.assertThat(logData.getLogs().get(1).getValue(),
				CoreMatchers.<Object>is(RuntimeException.class.getName()));
		Assert.assertThat(logData.getLogs().get(2).getValue(), is("testException"));
		assertNotNull(logData.getLogs().get(3).getValue());
	}

	private void assertHttpSpan(AbstractTracingSpan span) {
		assertThat(span.getOperationName(), is("/test-web/test"));
		assertThat(SpanHelper.getComponentId(span), is(2));
		List<TagValuePair> tags = SpanHelper.getTags(span);
		assertThat(tags.get(0).getValue(), is("http://127.0.0.1:8080/test-web/test"));
		assertThat(tags.get(1).getValue(), is("GET"));
		assertThat(tags.get(2).getValue(), is("a=1&b=test"));
		assertThat(span.isExit(), is(true));
	}

}
