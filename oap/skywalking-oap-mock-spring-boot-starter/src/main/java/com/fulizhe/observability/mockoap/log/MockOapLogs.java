package com.fulizhe.observability.mockoap.log;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志记录的两个纯函数式判定:级别抽取与"拒收自己"。
 *
 * <p>刻意不引用任何 shaded gRPC / 协议类型 —— 便于单测,也避免把埋点耦合扩散到整个包。
 */
final class MockOapLogs {

	private MockOapLogs() {
	}

	/** 采集器自己的日志都带这个标记(见各 mockoap 类的日志前缀)。 */
	static final String SELF_MARKER = "[mock-oap]";

	/** 独立成词的日志级别(用 lookaround 而非 \b,避免把 "information" 里的 info 当级别)。 */
	private static final Pattern LEVEL_IN_TEXT = Pattern.compile("(?<![A-Za-z])(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)(?![A-Za-z])");

	/**
	 * 尽力抽取日志级别。
	 *
	 * <p>SkyWalking 的 {@code LogData} 没有 level 字段,所以:先看 tags 里有没有 {@code level}
	 * (或 {@code log.level},大小写不敏感),没有再从正文里找独立成词的级别关键字。
	 * 抽取不到返回 {@code null}(页面按"未分级"展示)。
	 */
	static String detectLevel(String content, Map<String, String> tags) {
		if (tags != null) {
			for (Map.Entry<String, String> e : tags.entrySet()) {
				String k = e.getKey();
				if (k == null) {
					continue;
				}
				String lower = k.toLowerCase();
				if (("level".equals(lower) || "log.level".equals(lower)) && e.getValue() != null && !e.getValue().trim().isEmpty()) {
					return e.getValue().trim().toUpperCase();
				}
			}
		}
		if (content == null) {
			return null;
		}
		Matcher m = LEVEL_IN_TEXT.matcher(content);
		return m.find() ? m.group(1) : null;
	}

	/**
	 * 是否为采集器自身产生的日志。
	 *
	 * <p>必须拒收:collector 收到日志若再打 INFO/WARN,该日志又会被 {@code GRPCLogClientAppender}
	 * 发回本进程 → 无限放大。判据是日志正文带 {@link #SELF_MARKER},或正文里出现本包名
	 * ({@code logback} 的 {@code %logger} 会把 logger 名写进正文)。
	 */
	static boolean isSelfLog(MockOapLogRecord record) {
		if (record == null) {
			return false;
		}
		String content = record.getContent();
		if (content == null) {
			return false;
		}
		return content.contains(SELF_MARKER) || content.contains("com.fulizhe.observability.mockoap");
	}

	/** 截断超长字段,防止一条大日志把内存撑爆。 */
	static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max) + "...";
	}
}
