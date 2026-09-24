package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

/**
 * 指标查询的范围路由与上限常量（Phase 5）。
 * <p>
 * 约定：调用方以**分钟桶**给出 {@code [fromBucket, toBucket]}（对齐前端时间范围），
 * 由 {@link #routeResolution} 按跨度选分辨率；小时桶 = 分钟桶 / 60。
 * </p>
 */
public final class TraceMetricsQuery {

    /** 单次查询从存储取行的硬上限（超出即视为截断）。 */
    public static final int MAX_QUERY_POINTS = 2000;
    /** 查询行数默认上限。 */
    public static final int LIMIT_DEFAULT = 200;
    /** 查询行数上限。 */
    public static final int LIMIT_MAX = 1000;

    public static final long MINUTE_MS = 60_000L;
    public static final long HOUR_MS = 3_600_000L;

    /** 分钟表保留桶数（48h）。 */
    public static final long MINUTE_RETENTION_BUCKETS = 2880L;
    /** 小时表保留桶数（30d）。 */
    public static final long HOUR_RETENTION_BUCKETS = 720L;

    /** 小时桶内分钟数。 */
    public static final long MINUTES_PER_HOUR = 60L;

    private TraceMetricsQuery() {
    }

    /**
     * 按跨度选分辨率：{@code ≤ 24h} 用分钟表，否则用小时表。
     *
     * @param fromMinuteBucket 起始分钟桶（含）
     * @param toMinuteBucket   结束分钟桶（含）
     */
    public static String routeResolution(final long fromMinuteBucket, final long toMinuteBucket) {
        final long span = toMinuteBucket - fromMinuteBucket;
        return span <= 24L * 60L ? "minute" : "hour";
    }

    /** 缺省 200、下限 1、上限 1000。 */
    public static int clampLimit(final Integer limit) {
        if (limit == null) {
            return LIMIT_DEFAULT;
        }
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, LIMIT_MAX);
    }
}
