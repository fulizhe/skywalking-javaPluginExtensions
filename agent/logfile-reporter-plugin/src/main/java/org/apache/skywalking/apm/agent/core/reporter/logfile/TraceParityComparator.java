package org.apache.skywalking.apm.agent.core.reporter.logfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一致性比对器（纯函数）：输入旧快照、新快照、待比 traceIds，输出差异报告。
 * <p>
 * 比对维度：缺失 / logs 条数 / span 数 / 关键字段 / 解析失败。
 * 不操作任何外部状态，便于单元测试。
 * </p>
 */
public final class TraceParityComparator {

    /** 差异类型 */
    public enum DiffType {
        MISSING_IN_NEW,
        MISSING_IN_OLD,
        LOGS_COUNT,
        SPAN_COUNT,
        KEY_FIELD,
        PARSE_FAILURE
    }

    /** 单条差异 */
    public static final class DiffEntry {
        private final String traceId;
        private final DiffType type;
        private final String expected;
        private final String actual;
        private final String detail;

        DiffEntry(final String traceId, final DiffType type, final String expected, final String actual, final String detail) {
            this.traceId = traceId;
            this.type = type;
            this.expected = expected;
            this.actual = actual;
            this.detail = detail;
        }

        public String getTraceId() { return traceId; }
        public DiffType getType() { return type; }
        public String getExpected() { return expected; }
        public String getActual() { return actual; }
        public String getDetail() { return detail; }
    }

    /** 比对报告 */
    public static final class Report {
        private final int checkedCount;
        private final Map<DiffType, Integer> diffCounts;
        private final List<DiffEntry> samples;

        Report(final int checkedCount, final Map<DiffType, Integer> diffCounts, final List<DiffEntry> samples) {
            this.checkedCount = checkedCount;
            this.diffCounts = diffCounts;
            this.samples = samples;
        }

        public int getCheckedCount() { return checkedCount; }
        public Map<DiffType, Integer> getDiffCounts() { return Collections.unmodifiableMap(diffCounts); }
        public List<DiffEntry> getSamples() { return Collections.unmodifiableList(samples); }
        public boolean hasDiffs() {
            for (int v : diffCounts.values()) { if (v > 0) return true; }
            return false;
        }
        public int getTotalDiffs() {
            int total = 0;
            for (int v : diffCounts.values()) { total += v; }
            return total;
        }
    }

    private TraceParityComparator() {
    }

    /**
     * 比对旧快照与新快照中指定 traceIds 的数据一致性。
     *
     * @param oldSnapshot 旧内存视图快照（{@code data[traceId].logs}）
     * @param newSnapshot H2 影子存储快照
     * @param traceIds   本批涉及的 traceId 集合
     * @return 比对报告
     */
    @SuppressWarnings("unchecked")
    public static Report compare(
            final Map<String, Map<String, Object>> oldSnapshot,
            final Map<String, Map<String, Object>> newSnapshot,
            final Set<String> traceIds) {

        final Map<DiffType, Integer> diffCounts = new EnumMap<DiffType, Integer>(DiffType.class);
        for (DiffType t : DiffType.values()) {
            diffCounts.put(t, 0);
        }
        final List<DiffEntry> samples = new ArrayList<DiffEntry>();
        int checked = 0;

        for (final String traceId : traceIds) {
            checked++;
            final Map<String, Object> oldData = oldSnapshot != null ? oldSnapshot.get(traceId) : null;
            final Map<String, Object> newData = newSnapshot != null ? newSnapshot.get(traceId) : null;

            // 缺失检查
            if (oldData == null && newData == null) {
                continue;
            }
            if (oldData != null && newData == null) {
                addDiff(diffCounts, samples, traceId, DiffType.MISSING_IN_NEW, String.valueOf(getLogsCount(oldData)), "0", "traceId present in old but missing in new");
                continue;
            }
            if (oldData == null && newData != null) {
                addDiff(diffCounts, samples, traceId, DiffType.MISSING_IN_OLD, "0", String.valueOf(getLogsCount(newData)), "traceId missing in old but present in new");
                continue;
            }

            // logs 条数比对
            final int oldLogsCount = getLogsCount(oldData);
            final int newLogsCount = getLogsCount(newData);
            if (oldLogsCount != newLogsCount) {
                addDiff(diffCounts, samples, traceId, DiffType.LOGS_COUNT, String.valueOf(oldLogsCount), String.valueOf(newLogsCount), "logs count mismatch");
                continue;
            }

            // 逐 segment 比对 span 数与关键字段。
            // 两侧排序口径不同(旧 store 按到达顺序 append,H2 按 start_time 排序),
            // 直接按下标对齐会产生假差异;统一切到按 traceSegmentId 排序后再逐位比对,
            // 使对齐与到达/落库顺序无关(segmentId 在同一 trace 内唯一)。
            final List<Map<String, Object>> oldLogs = sortBySegmentId(getLogsList(oldData));
            final List<Map<String, Object>> newLogs = sortBySegmentId(getLogsList(newData));
            if (oldLogs == null || newLogs == null) {
                addDiff(diffCounts, samples, traceId, DiffType.PARSE_FAILURE, "logs list", "null", "failed to parse logs list");
                continue;
            }

            boolean spanDiff = false;
            boolean fieldDiff = false;
            for (int i = 0; i < oldLogs.size() && i < newLogs.size(); i++) {
                final Map<String, Object> oldLog = oldLogs.get(i);
                final Map<String, Object> newLog = newLogs.get(i);

                final int oldSpanCount = getSpanCount(oldLog);
                final int newSpanCount = getSpanCount(newLog);
                if (oldSpanCount != newSpanCount && !spanDiff) {
                    addDiff(diffCounts, samples, traceId, DiffType.SPAN_COUNT, String.valueOf(oldSpanCount), String.valueOf(newSpanCount), "span count mismatch at log index " + i);
                    spanDiff = true;
                }

                // 关键字段比对：traceSegmentId
                final String oldSegId = asString(oldLog.get("traceSegmentId"));
                final String newSegId = asString(newLog.get("traceSegmentId"));
                if (!safeEquals(oldSegId, newSegId) && !fieldDiff) {
                    addDiff(diffCounts, samples, traceId, DiffType.KEY_FIELD, oldSegId, newSegId, "traceSegmentId mismatch at log index " + i);
                    fieldDiff = true;
                }
            }
        }

        return new Report(checked, diffCounts, samples);
    }

    private static void addDiff(final Map<DiffType, Integer> counts, final List<DiffEntry> samples,
            final String traceId, final DiffType type, final String expected, final String actual, final String detail) {
        counts.put(type, counts.get(type) + 1);
        // 每种类型只保留一条样例
        boolean hasSample = false;
        for (DiffEntry s : samples) {
            if (s.getType() == type) {
                hasSample = true;
                break;
            }
        }
        if (!hasSample) {
            samples.add(new DiffEntry(traceId, type, expected, actual, detail));
        }
    }

    @SuppressWarnings("unchecked")
    private static int getLogsCount(final Map<String, Object> data) {
        if (data == null) return 0;
        final Object logs = data.get("logs");
        if (logs instanceof List) {
            return ((List<Object>) logs).size();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getLogsList(final Map<String, Object> data) {
        if (data == null) return null;
        final Object logs = data.get("logs");
        if (logs instanceof List) {
            return (List<Map<String, Object>>) logs;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static int getSpanCount(final Map<String, Object> log) {
        if (log == null) return 0;
        final Object spans = log.get("spans");
        if (spans instanceof List) {
            return ((List<Object>) spans).size();
        }
        return 0;
    }

    private static String asString(final Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean safeEquals(final String a, final String b) {
        return (a == null) ? (b == null) : a.equals(b);
    }

    /**
     * 按 {@code traceSegmentId} 排序的副本，使两侧对齐与到达/落库顺序无关。
     * null 先于非 null；不会就地修改入参。
     */
    private static List<Map<String, Object>> sortBySegmentId(final List<Map<String, Object>> logs) {
        if (logs == null) {
            return null;
        }
        final List<Map<String, Object>> copy = new ArrayList<Map<String, Object>>(logs);
        Collections.sort(copy, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(final Map<String, Object> a, final Map<String, Object> b) {
                final String idA = asString(a.get("traceSegmentId"));
                final String idB = asString(b.get("traceSegmentId"));
                if (idA == null) {
                    return idB == null ? 0 : -1;
                }
                if (idB == null) {
                    return 1;
                }
                return idA.compareTo(idB);
            }
        });
        return copy;
    }
}
