package org.apache.skywalking.apm.toolkit.trace;

import java.util.Collections;
import java.util.Map;

/**
 * Refer To {@code TraceContext}
 * @author LQ
 *
 */
public class SWUtils {
	/**
	 * 开启/关闭 SW 调试信息
	 * @author LQ
	 *
	 */
	public static void toggleDebug() {
	}

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
}
