package org.apache.skywalking.apm.agent.core.reporter.logfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

/**
 * TraceParityComparator 单元测试。
 * <p>
 * 覆盖各差异类型与"零差异"路径。比对器为纯函数，直接喂两份 Map 断言。
 * </p>
 */
public class TraceParityComparatorTest {

    @Test
    public void zeroDiffs_whenSnapshotsIdentical() {
        final Map<String, Map<String, Object>> old = buildSnapshot("t1", buildLogMap("s1", 2));
        final Map<String, Map<String, Object>> now = buildSnapshot("t1", buildLogMap("s1", 2));
        final Set<String> ids = setOf("t1");

        final TraceParityComparator.Report report = TraceParityComparator.compare(old, now, ids);

        Assert.assertEquals("checked should be 1", 1, report.getCheckedCount());
        Assert.assertFalse("should have no diffs", report.hasDiffs());
        Assert.assertEquals("total diffs should be 0", 0, report.getTotalDiffs());
    }

    @Test
    public void missingInNew_whenTraceIdOnlyInOld() {
        final Map<String, Map<String, Object>> old = buildSnapshot("t1", buildLogMap("s1", 1));
        final Map<String, Map<String, Object>> now = new HashMap<String, Map<String, Object>>();
        final Set<String> ids = setOf("t1");

        final TraceParityComparator.Report report = TraceParityComparator.compare(old, now, ids);

        Assert.assertTrue("should have diffs", report.hasDiffs());
        Assert.assertEquals("MISSING_IN_NEW count should be 1",
                Integer.valueOf(1), report.getDiffCounts().get(TraceParityComparator.DiffType.MISSING_IN_NEW));
    }

    @Test
    public void missingInOld_whenTraceIdOnlyInNew() {
        final Map<String, Map<String, Object>> old = new HashMap<String, Map<String, Object>>();
        final Map<String, Map<String, Object>> now = buildSnapshot("t1", buildLogMap("s1", 1));
        final Set<String> ids = setOf("t1");

        final TraceParityComparator.Report report = TraceParityComparator.compare(old, now, ids);

        Assert.assertTrue("should have diffs", report.hasDiffs());
        Assert.assertEquals("MISSING_IN_OLD count should be 1",
                Integer.valueOf(1), report.getDiffCounts().get(TraceParityComparator.DiffType.MISSING_IN_OLD));
    }

    @Test
    public void logsCountDiff_whenDifferentLogCounts() {
        final Map<String, Map<String, Object>> old = buildSnapshot("t1",
                buildLogMap("s1", 1), buildLogMap("s2", 1));
        final Map<String, Map<String, Object>> now = buildSnapshot("t1", buildLogMap("s1", 1));
        final Set<String> ids = setOf("t1");

        final TraceParityComparator.Report report = TraceParityComparator.compare(old, now, ids);

        Assert.assertTrue("should have diffs", report.hasDiffs());
        Assert.assertEquals("LOGS_COUNT diff should be 1",
                Integer.valueOf(1), report.getDiffCounts().get(TraceParityComparator.DiffType.LOGS_COUNT));
    }

    @Test
    public void spanCountDiff_whenDifferentSpanCounts() {
        final Map<String, Map<String, Object>> old = buildSnapshot("t1", buildLogMap("s1", 3));
        final Map<String, Map<String, Object>> now = buildSnapshot("t1", buildLogMap("s1", 2));
        final Set<String> ids = setOf("t1");

        final TraceParityComparator.Report report = TraceParityComparator.compare(old, now, ids);

        Assert.assertEquals("SPAN_COUNT diff should be 1",
                Integer.valueOf(1), report.getDiffCounts().get(TraceParityComparator.DiffType.SPAN_COUNT));
        Assert.assertFalse("should not have LOGS_COUNT diff",
                report.getDiffCounts().get(TraceParityComparator.DiffType.LOGS_COUNT) > 0);
    }

    @Test
    public void keyFieldDiff_whenSegmentIdMismatch() {
        final Map<String, Map<String, Object>> old = buildSnapshot("t1", buildLogMap("s-old", 1));
        final Map<String, Map<String, Object>> now = buildSnapshot("t1", buildLogMap("s-new", 1));
        final Set<String> ids = setOf("t1");

        final TraceParityComparator.Report report = TraceParityComparator.compare(old, now, ids);

        Assert.assertEquals("KEY_FIELD diff should be 1",
                Integer.valueOf(1), report.getDiffCounts().get(TraceParityComparator.DiffType.KEY_FIELD));
    }

    @Test
    public void multipleTraceIds_mixedResults() {
        // t1: identical (zero diff)
        // t2: missing in new
        // t3: logs count diff
        final Map<String, Map<String, Object>> old = new LinkedHashMap<String, Map<String, Object>>();
        old.put("t1", buildTraceData(buildLogMap("s1", 2)));
        old.put("t2", buildTraceData(buildLogMap("s2", 1)));
        old.put("t3", buildTraceData(buildLogMap("s3", 1), buildLogMap("s4", 1)));

        final Map<String, Map<String, Object>> now = new LinkedHashMap<String, Map<String, Object>>();
        now.put("t1", buildTraceData(buildLogMap("s1", 2)));
        // t2 missing in new
        now.put("t3", buildTraceData(buildLogMap("s3", 1))); // only 1 log, old has 2

        final Set<String> ids = new HashSet<String>(Arrays.asList("t1", "t2", "t3"));

        final TraceParityComparator.Report report = TraceParityComparator.compare(old, now, ids);

        Assert.assertEquals("checked should be 3", 3, report.getCheckedCount());
        Assert.assertTrue("should have diffs", report.hasDiffs());
        Assert.assertEquals("MISSING_IN_NEW should be 1",
                Integer.valueOf(1), report.getDiffCounts().get(TraceParityComparator.DiffType.MISSING_IN_NEW));
        Assert.assertEquals("LOGS_COUNT should be 1",
                Integer.valueOf(1), report.getDiffCounts().get(TraceParityComparator.DiffType.LOGS_COUNT));
        Assert.assertEquals("total diffs should be 2", 2, report.getTotalDiffs());
    }

    @Test
    public void nullSnapshots_handledGracefully() {
        final TraceParityComparator.Report report = TraceParityComparator.compare(null, null, setOf("t1"));
        Assert.assertEquals("checked should be 1", 1, report.getCheckedCount());
        // Both null → no MISSING diffs (both absent)
        Assert.assertFalse("should have no diffs", report.hasDiffs());
    }

    @Test
    public void emptyTraceIdSet_returnsZeroChecked() {
        final TraceParityComparator.Report report = TraceParityComparator.compare(
                new HashMap<String, Map<String, Object>>(),
                new HashMap<String, Map<String, Object>>(),
                Collections.<String>emptySet());
        Assert.assertEquals("checked should be 0", 0, report.getCheckedCount());
        Assert.assertFalse("should have no diffs", report.hasDiffs());
    }

    @Test
    public void samplesLimitedToOnePerType() {
        // Two traceIds with same diff type (LOGS_COUNT)
        final Map<String, Map<String, Object>> old = new LinkedHashMap<String, Map<String, Object>>();
        old.put("t1", buildTraceData(buildLogMap("s1", 1), buildLogMap("s2", 1)));
        old.put("t2", buildTraceData(buildLogMap("s3", 1), buildLogMap("s4", 1)));

        final Map<String, Map<String, Object>> now = new LinkedHashMap<String, Map<String, Object>>();
        now.put("t1", buildTraceData(buildLogMap("s1", 1)));
        now.put("t2", buildTraceData(buildLogMap("s3", 1)));

        final TraceParityComparator.Report report = TraceParityComparator.compare(old, now, setOf("t1", "t2"));

        Assert.assertEquals("LOGS_COUNT count should be 2",
                Integer.valueOf(2), report.getDiffCounts().get(TraceParityComparator.DiffType.LOGS_COUNT));
        // Only 1 sample for LOGS_COUNT
        int logCountSamples = 0;
        for (TraceParityComparator.DiffEntry s : report.getSamples()) {
            if (s.getType() == TraceParityComparator.DiffType.LOGS_COUNT) {
                logCountSamples++;
            }
        }
        Assert.assertEquals("should have 1 LOGS_COUNT sample", 1, logCountSamples);
    }

    // ========== helpers ==========

    private static Map<String, Map<String, Object>> buildSnapshot(final String traceId, final Map<String, Object>... logs) {
        return Collections.singletonMap(traceId, buildTraceData(logs));
    }

    @SafeVarargs
    private static Map<String, Object> buildTraceData(final Map<String, Object>... logs) {
        final Map<String, Object> data = new LinkedHashMap<String, Object>();
        final List<Map<String, Object>> logList = new ArrayList<Map<String, Object>>();
        Collections.addAll(logList, logs);
        data.put("logs", logList);
        return data;
    }

    private static Map<String, Object> buildLogMap(final String segmentId, final int spanCount) {
        final Map<String, Object> logMap = new LinkedHashMap<String, Object>();
        logMap.put("traceSegmentId", segmentId);
        logMap.put("service", "test-service");
        logMap.put("serviceInstance", "test-instance");
        logMap.put("isSizeLimited", false);

        final List<Map<String, Object>> spans = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < spanCount; i++) {
            final Map<String, Object> span = new LinkedHashMap<String, Object>();
            span.put("spanId", i);
            span.put("parentSpanId", i - 1);
            span.put("operationName", "op-" + i);
            span.put("startTime", 1000L + i * 100);
            span.put("endTime", 1100L + i * 100);
            span.put("spanType", "Entry");
            span.put("isError", false);
            spans.add(span);
        }
        logMap.put("spans", spans);
        return logMap;
    }

    private static Set<String> setOf(final String... ids) {
        return new HashSet<String>(Arrays.asList(ids));
    }
}
