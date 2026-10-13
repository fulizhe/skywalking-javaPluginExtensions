package org.apache.skywalking.apm.toolkit.trace;

/**
 * 运行时调试开关的入口类 (拷贝模板)。
 * <p>
 * 交由 skywalking-agent 增强: {@code DynamicDebugRuntimeInstrumentation} 拦截本类的
 * {@link #enableDebug()} / {@link #disableDebug()}, 由两个拦截器委托
 * {@code DynamicDebugRuntimeSwitch} 反射改写各官方插件 {@code PluginConfig} 的采集开关。
 * <p>
 * 用法: 把本文件按原 package 结构 ({@code org.apache.skywalking.apm.toolkit.trace})
 * 拷贝到自己的项目中, <b>不要改 package 与类名</b>, 然后在任意位置调用
 * {@code SWDebugUtils.enableDebug()} / {@code SWDebugUtils.disableDebug()}。
 * <p>
 * Refer To {@code TraceContext}.
 *
 * @author LQ
 */
public class SWDebugUtils {
	/**
	 * 打开 SW 调试信息(更详尽的链路上下文采集): feign / jdbc / httpclient / springmvc / tomcat。
	 */
	public static void enableDebug() {
	}

	/**
	 * 关闭 SW 调试信息(恢复正常运行的采集粒度)。
	 */
	public static void disableDebug() {
	}
}
