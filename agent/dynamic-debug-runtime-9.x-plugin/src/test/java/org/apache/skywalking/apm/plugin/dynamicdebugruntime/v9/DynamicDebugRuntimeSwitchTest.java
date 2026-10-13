package org.apache.skywalking.apm.plugin.dynamicdebugruntime.v9;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.skywalking.apm.plugin.feign.http.v9.FeignPluginConfig;
import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.apache.skywalking.apm.plugin.jdbc.JDBCPluginConfig;
import org.apache.skywalking.apm.plugin.spring.mvc.commons.SpringMVCPluginConfig;
import org.junit.After;
import org.junit.Test;

/**
 * 验证 enable / disable 两个拦截器委托的 {@link DynamicDebugRuntimeSwitch}:
 * <ul>
 *   <li>能把各插件 {@code PluginConfig} 的静态开关反射改写为指定值;</li>
 *   <li>某个插件的 config 类缺失(以测试类路径下无 Tomcat 配置模拟)时不影响其余开关;</li>
 *   <li>无 trace 上下文时 {@code handleMethodException} 不二次抛错。</li>
 * </ul>
 */
public class DynamicDebugRuntimeSwitchTest {

	private final DynamicDebugRuntimeEnableInterceptor enable = new DynamicDebugRuntimeEnableInterceptor();
	private final DynamicDebugRuntimeDisableInterceptor disable = new DynamicDebugRuntimeDisableInterceptor();

	@After
	public void resetSwitches() {
		FeignPluginConfig.Plugin.Feign.COLLECT_REQUEST_BODY = false;
		JDBCPluginConfig.Plugin.JDBC.TRACE_SQL_PARAMETERS = false;
		HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS = false;
		SpringMVCPluginConfig.Plugin.SpringMVC.COLLECT_HTTP_PARAMS = false;
	}

	@Test
	public void enableTurnsAllOnAndToleratesMissingPlugin() {
		// 测试类路径下没有 TomcatPluginConfig(模拟该插件未装配); 若容错失效, beforeMethod 会抛出。
		enable.beforeMethod(Object.class, null, new Object[0], new Class<?>[0], null);

		assertTrue(FeignPluginConfig.Plugin.Feign.COLLECT_REQUEST_BODY);
		assertTrue(JDBCPluginConfig.Plugin.JDBC.TRACE_SQL_PARAMETERS);
		assertTrue(HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS);
		assertTrue(SpringMVCPluginConfig.Plugin.SpringMVC.COLLECT_HTTP_PARAMS);
	}

	@Test
	public void disableTurnsAllOff() {
		enable.beforeMethod(Object.class, null, new Object[0], new Class<?>[0], null);
		disable.beforeMethod(Object.class, null, new Object[0], new Class<?>[0], null);

		assertFalse(FeignPluginConfig.Plugin.Feign.COLLECT_REQUEST_BODY);
		assertFalse(JDBCPluginConfig.Plugin.JDBC.TRACE_SQL_PARAMETERS);
		assertFalse(HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS);
		assertFalse(SpringMVCPluginConfig.Plugin.SpringMVC.COLLECT_HTTP_PARAMS);
	}

	@Test
	public void handleMethodExceptionDoesNotThrowWithoutActiveSpan() {
		// 无 trace 上下文: ContextManager.isActive() 为 false, 不应因解引用空 span 而二次抛错。
		enable.handleMethodException(Object.class, null, null, null, new RuntimeException("boom"));
	}
}
