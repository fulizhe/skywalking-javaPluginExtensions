package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标行合并工具（Phase 5）：小时 rollup 与查询降采样共用同一套合并语义。
 * <p>
 * 计数 / 总耗时 / 最大耗时 / 样本数为**精确合并**（求和 / 取最大 / 求和）；
 * 分位数按 {@code request_count} **加权平均**（近似，v1 已知偏差，见 spec）。
 * 组内任一行某分位缺失（样本不足）时，合并结果的该分位也置空。
 * </p>
 */
public final class TraceMetricsRollup {

    private TraceMetricsRollup() {
    }

    /**
     * 把某一小时的分钟行按 endpoint 合并为小时行（含全局保留键 {@code "*"}）。
     *
     * @param minuteRows 该小时内的分钟行（endpoint 可混合）
     * @param hourBucket 目标小时桶（{@code startTime / 3600000}）
     */
    public static List<MetricsRow> toHourRows(final List<MetricsRow> minuteRows, final long hourBucket) {
        return mergeByEndpoint(minuteRows, hourBucket);
    }

    /**
     * 按 endpoint 合并任意行集合为「每端点一行」（与 rollup 同口径）。
     * <p>
     * 用于：小时 rollup、以及读口「按端点聚合」把 SQL 聚合结果与内存实时窗口合并。
     * 输出行的 {@code timeBucket} = {@code bucket}（聚合场景传 0 占位）。
     * </p>
     */
    public static List<MetricsRow> mergeByEndpoint(final List<MetricsRow> rows, final long bucket) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<MetricsRow>();
        }
        final Map<String, List<MetricsRow>> byEndpoint = groupByEndpoint(rows);
        final List<MetricsRow> out = new ArrayList<MetricsRow>(byEndpoint.size());
        for (Map.Entry<String, List<MetricsRow>> entry : byEndpoint.entrySet()) {
            out.add(merge(entry.getValue(), bucket, entry.getKey()));
        }
        return out;
    }

    /**
     * 等距降采样：把行数压到不超过 {@code target}（按 endpoint 分组、组内等距合并）。
     * 行数已不超限时原样返回。
     */
    public static List<MetricsRow> downsample(final List<MetricsRow> rows, final int target) {
        if (rows == null || rows.isEmpty() || target <= 0 || rows.size() <= target) {
            return rows;
        }
        final Map<String, List<MetricsRow>> byEndpoint = groupByEndpoint(rows);
        final int perEndpoint = Math.max(1, target / Math.max(1, byEndpoint.size()));
        final List<MetricsRow> out = new ArrayList<MetricsRow>();
        for (Map.Entry<String, List<MetricsRow>> entry : byEndpoint.entrySet()) {
            final List<MetricsRow> list = entry.getValue();
            if (list.size() <= perEndpoint) {
                out.addAll(list);
                continue;
            }
            for (int g = 0; g < perEndpoint; g++) {
                final int start = (int) ((long) g * list.size() / perEndpoint);
                final int end = (int) ((long) (g + 1) * list.size() / perEndpoint);
                if (end <= start) {
                    continue;
                }
                final List<MetricsRow> slice = list.subList(start, end);
                out.add(merge(slice, slice.get(0).getTimeBucket(), entry.getKey()));
            }
        }
        return out;
    }

    private static Map<String, List<MetricsRow>> groupByEndpoint(final List<MetricsRow> rows) {
        final Map<String, List<MetricsRow>> byEndpoint = new LinkedHashMap<String, List<MetricsRow>>();
        for (MetricsRow row : rows) {
            List<MetricsRow> list = byEndpoint.get(row.getEndpoint());
            if (list == null) {
                list = new ArrayList<MetricsRow>();
                byEndpoint.put(row.getEndpoint(), list);
            }
            list.add(row);
        }
        return byEndpoint;
    }

    private static MetricsRow merge(final List<MetricsRow> group, final long bucket, final String endpoint) {
        String service = null;
        long request = 0L;
        long error = 0L;
        long slow = 0L;
        long total = 0L;
        long max = 0L;
        int sample = 0;
        long w50 = 0L, w90 = 0L, w95 = 0L, w99 = 0L;
        long c50 = 0L, c90 = 0L, c95 = 0L, c99 = 0L;
        boolean has50 = true, has90 = true, has95 = true, has99 = true;
        for (MetricsRow row : group) {
            if (service == null) {
                service = row.getService();
            }
            request += row.getRequestCount();
            error += row.getErrorCount();
            slow += row.getSlowCount();
            total += row.getTotalLatency();
            if (row.getMaxLatency() > max) {
                max = row.getMaxLatency();
            }
            sample += row.getSampleCount();
            final long weight = row.getRequestCount();
            if (row.getP50() >= 0) {
                w50 += (long) row.getP50() * weight;
                c50 += weight;
            } else {
                has50 = false;
            }
            if (row.getP90() >= 0) {
                w90 += (long) row.getP90() * weight;
                c90 += weight;
            } else {
                has90 = false;
            }
            if (row.getP95() >= 0) {
                w95 += (long) row.getP95() * weight;
                c95 += weight;
            } else {
                has95 = false;
            }
            if (row.getP99() >= 0) {
                w99 += (long) row.getP99() * weight;
                c99 += weight;
            } else {
                has99 = false;
            }
        }
        final int p50 = (has50 && c50 > 0L) ? (int) (w50 / c50) : -1;
        final int p90 = (has90 && c90 > 0L) ? (int) (w90 / c90) : -1;
        final int p95 = (has95 && c95 > 0L) ? (int) (w95 / c95) : -1;
        final int p99 = (has99 && c99 > 0L) ? (int) (w99 / c99) : -1;
        return new MetricsRow(service, endpoint, bucket, request, error, slow, total, max,
                p50, p90, p95, p99, sample);
    }
}
