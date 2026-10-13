package org.apache.skywalking.apm.plugin.dynamicdebugruntime.v9;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.apache.skywalking.apm.plugin.feign.http.v9.FeignPluginConfig;
import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.apache.skywalking.apm.plugin.jdbc.JDBCPluginConfig;
import org.apache.skywalking.apm.plugin.spring.mvc.commons.SpringMVCPluginConfig;
import org.apache.skywalking.apm.plugin.tomcat10x.TomcatPluginConfig;
import org.junit.After;
import org.junit.Test;

/**
 * 验证 enable / disable 两个拦截器委托的 {@link DynamicDebugRuntimeSwitch}:
 * <ul>
 *   <li>能把各插件 {@code PluginConfig} 的静态布尔开关反射改写为指定值(feign / jdbc / httpclient /
 *       springmvc / tomcat10x / dubbo2x / dubbo3x);</li>
 *   <li>{@code INCLUDE_HTTP_HEADERS} 是 List 而非布尔 —— disable 清空、enable 恢复 agent 配置的名单;</li>
 *   <li>某个插件的 config 类缺失(以测试类路径下无 Tomcat 7/8 配置模拟)时不影响其余开关;</li>
 *   <li>无 trace 上下文时 {@code handleMethodException} 不二次抛错。</li>
 * </ul>
 */
public class DynamicDebugRuntimeSwitchTest {

	private static final java.util.List<String> CONFIGURED_HEADERS = Arrays.asList("trace-id", "tenant-id");

	static {
		// 模拟 agent 配置 plugin.springmvc.include_http_headers 解析出的名单。
		// 必须在任何 toggle 之前设置 —— 开关只在首次触碰时抓取一次。
		SpringMVCPluginConfig.Plugin.Http.INCLUDE_HTTP_HEADERS = Arrays.asList("trace-id", "tenant-id");
	}

	private final DynamicDebugRuntimeEnableInterceptor enable = new DynamicDebugRuntimeEnableInterceptor();
	private final DynamicDebugRuntimeDisableInterceptor disable = new DynamicDebugRuntimeDisableInterceptor();

	@After
	public void resetSwitches() {
		FeignPluginConfig.Plugin.Feign.COLLECT_REQUEST_BODY = false;
		JDBCPluginConfig.Plugin.JDBC.TRACE_SQL_PARAMETERS = false;
		HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS = false;
		SpringMVCPluginConfig.Plugin.SpringMVC.COLLECT_HTTP_PARAMS = false;
		TomcatPluginConfig.Plugin.Tomcat.COLLECT_HTTP_PARAMS = false;
		org.apache.skywalking.apm.plugin.asf.dubbo.DubboPluginConfig.Plugin.Dubbo.COLLECT_CONSUMER_ARGUMENTS = false;
		org.apache.skywalking.apm.plugin.asf.dubbo.DubboPluginConfig.Plugin.Dubbo.COLLECT_PROVIDER_ARGUMENTS = false;
		org.apache.skywalking.apm.plugin.asf.dubbo3.DubboPluginConfig.Plugin.Dubbo.COLLECT_CONSUMER_ARGUMENTS = false;
		org.apache.skywalking.apm.plugin.asf.dubbo3.DubboPluginConfig.Plugin.Dubbo.COLLECT_PROVIDER_ARGUMENTS = false;
		SpringMVCPluginConfig.Plugin.Http.INCLUDE_HTTP_HEADERS = Arrays.asList("trace-id", "tenant-id");
	}

	@Test
	public void enableTurnsAllOnAndToleratesMissingPlugin() {
		// 测试类路径下没有 Tomcat 7/8 的 config(模拟该插件未装配); 若容错失效, beforeMethod 会抛出。
		enable.beforeMethod(Object.class, null, new Object[0], new Class<?>[0], null);

		assertTrue(FeignPluginConfig.Plugin.Feign.COLLECT_REQUEST_BODY);
		assertTrue(JDBCPluginConfig.Plugin.JDBC.TRACE_SQL_PARAMETERS);
		assertTrue(HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS);
		assertTrue(SpringMVCPluginConfig.Plugin.SpringMVC.COLLECT_HTTP_PARAMS);
		assertTrue(TomcatPluginConfig.Plugin.Tomcat.COLLECT_HTTP_PARAMS);
		assertTrue(org.apache.skywalking.apm.plugin.asf.dubbo.DubboPluginConfig.Plugin.Dubbo.COLLECT_CONSUMER_ARGUMENTS);
		assertTrue(org.apache.skywalking.apm.plugin.asf.dubbo.DubboPluginConfig.Plugin.Dubbo.COLLECT_PROVIDER_ARGUMENTS);
		assertTrue(org.apache.skywalking.apm.plugin.asf.dubbo3.DubboPluginConfig.Plugin.Dubbo.COLLECT_CONSUMER_ARGUMENTS);
		assertTrue(org.apache.skywalking.apm.plugin.asf.dubbo3.DubboPluginConfig.Plugin.Dubbo.COLLECT_PROVIDER_ARGUMENTS);
	}

	@Test
	public void disableTurnsAllOff() {
		enable.beforeMethod(Object.class, null, new Object[0], new Class<?>[0], null);
		disable.beforeMethod(Object.class, null, new Object[0], new Class<?>[0], null);

		assertFalse(FeignPluginConfig.Plugin.Feign.COLLECT_REQUEST_BODY);
		assertFalse(JDBCPluginConfig.Plugin.JDBC.TRACE_SQL_PARAMETERS);
		assertFalse(HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS);
		assertFalse(SpringMVCPluginConfig.Plugin.SpringMVC.COLLECT_HTTP_PARAMS);
		assertFalse(TomcatPluginConfig.Plugin.Tomcat.COLLECT_HTTP_PARAMS);
		assertFalse(org.apache.skywalking.apm.plugin.asf.dubbo.DubboPluginConfig.Plugin.Dubbo.COLLECT_CONSUMER_ARGUMENTS);
		assertFalse(org.apache.skywalking.apm.plugin.asf.dubbo.DubboPluginConfig.Plugin.Dubbo.COLLECT_PROVIDER_ARGUMENTS);
		assertFalse(org.apache.skywalking.apm.plugin.asf.dubbo3.DubboPluginConfig.Plugin.Dubbo.COLLECT_CONSUMER_ARGUMENTS);
		assertFalse(org.apache.skywalking.apm.plugin.asf.dubbo3.DubboPluginConfig.Plugin.Dubbo.COLLECT_PROVIDER_ARGUMENTS);
	}

	@Test
	public void springMvcHeadersClearedOnDisableAndRestoredOnEnable() {
		disable.beforeMethod(Object.class, null, new Object[0], new Class<?>[0], null);
		assertTrue(SpringMVCPluginConfig.Plugin.Http.INCLUDE_HTTP_HEADERS.isEmpty());

		enable.beforeMethod(Object.class, null, new Object[0], new Class<?>[0], null);
		assertEquals(CONFIGURED_HEADERS, SpringMVCPluginConfig.Plugin.Http.INCLUDE_HTTP_HEADERS);
	}

	@Test
	public void handleMethodExceptionDoesNotThrowWithoutActiveSpan() {
		// 无 trace 上下文: ContextManager.isActive() 为 false, 不应因解引用空 span 而二次抛错。
		enable.handleMethodException(Object.class, null, null, null, new RuntimeException("boom"));
	}
}
