package org.apache.skywalking.apm.toolkit.trace;

import java.util.Collections;
import java.util.Map;

/**
 * Refer To {@code TraceContext}
 * @author LQ
 *
 */
public class SWLogFileReporterUtils {
	/**
	 * 开启/关闭 SW Agent向Server推送信息
	 * @author LQ
	 * @return 操作完成后的状态描述信息. 为true代表操作成功
	 */	
	public static Object toggleAgentPushDataToServer(boolean isEnable) {
		return false;
	}	
	
	/**
	 * 向外界报告内部状态
	 */	
	public static Map<String, Boolean> getAllStatus() {
		return Collections.emptyMap();
	}

	public static void enableReport(Map<String, Object> config) {
		// 配置项
		// 1. 对于哪些请求进行捕获. 基于request的Spel进行判断
		// 2. 日志存放根目录. / 直接存入缓存, 借鉴druid
		// 3.
		return false;
	}

	public static void disableReport(Map<String, Object> config) {
		return false;
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
}
