package org.apache.skywalking.apm.toolkit;

import java.util.Collections;
import java.util.Map;

/**
 * <p>
 * Refer To {@code TraceContext}
 * <p>
 * 他奶奶地，这个类千万不能定义在 org.apache.skywalking.apm.agent.core.XXX 目录下. 浪费我一上午.
 * 
 * @author LQ
 *
 */
public class SWLogfileReporterUtils2222222222222 {

	static {
		// 静态代码块，会在类加载时执行
		System.out.println("### SWLogfileReporterUtils class loaded222222.");
	}

	public static void enableReport(Map<String, Object> config) {
		// 配置项
		// 1. 对于哪些请求进行捕获. 基于request的Spel进行判断
		// 2. 日志存放根目录. / 直接存入缓存, 借鉴druid
		// 3.
	}

	public static void disableReport(Map<String, Object> config) {
	}

	/**
	 * 向外界报告内部状态
	 */
	public static Map<String, Object> statisticStatus() {
		// 1. 缓存里的配置键值对(用户过往传入的)
		// 2. 缓存里记录的日志(根据用户配置, 由sw捕获的)
		// 3. 这里试着把缓存传出去，能不能让外面直接操作
		return Collections.emptyMap();
	}

	/**
	 * 性能剖析
	 */
	public static Map<String, Object> startProfile(Map<String, Object> params) {
		return Collections.emptyMap();
	}

	/**
	 * 性能剖析
	 */
	public static Map<String, Object> getProfileDatas() {
		return Collections.emptyMap();
	}
}
