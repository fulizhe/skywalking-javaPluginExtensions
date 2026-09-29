package org.apache.skywalking.apm.agent.core.reporter.logfile.storage;

final class H2SqlStatements {

    /**
     * mem 模式每次启动即重建：先 DROP 再 CREATE，保证删列（{@code trace_level}）即时生效、
     * 无残留旧 schema；同 JVM 内多实例共享固定 mem 库时也保持一致结构（数据隔离由 {@code clear()} 负责）。
     */
    static final String DROP_SEGMENT_TABLE_SQL = "DROP TABLE IF EXISTS trace_segment";

    static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS trace_segment ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
            + "trace_id VARCHAR(128) NOT NULL, "
            + "segment_id VARCHAR(128) NOT NULL, "
            + "endpoint VARCHAR(512), "
            + "start_time BIGINT NOT NULL, "
            + "end_time BIGINT NOT NULL, "
            + "latency INT NOT NULL, "
            + "is_error BOOLEAN NOT NULL, "
            + "payload_id BIGINT, "
            + "time_bucket BIGINT NOT NULL, "
            + "create_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
            + ")";

    static final String CREATE_INDEX_SQL = "CREATE INDEX IF NOT EXISTS idx_trace_segment_trace_id ON trace_segment(trace_id)";

    static final String CREATE_AUDIT_TABLE_SQL = "CREATE TABLE IF NOT EXISTS trace_parity_audit ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
            + "check_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
            + "trace_id VARCHAR(128), "
            + "diff_type VARCHAR(32), "
            + "expected VARCHAR(2048), "
            + "actual VARCHAR(2048), "
            + "detail VARCHAR(2048)"
            + ")";

    static final String INSERT_AUDIT_SQL = "INSERT INTO trace_parity_audit (trace_id, diff_type, expected, actual, detail) VALUES (?, ?, ?, ?, ?)";

    static final String MAX_AUDIT_ID_SQL = "SELECT MAX(id) FROM trace_parity_audit";

    static final String DELETE_AUDIT_CAP_SQL = "DELETE FROM trace_parity_audit WHERE id <= ?";

    static final String SELECT_AUDIT_RECENT_SQL = "SELECT check_time, trace_id, diff_type, expected, actual, detail "
            + "FROM trace_parity_audit ORDER BY id DESC LIMIT ?";

    static final String COUNT_AUDIT_SQL = "SELECT COUNT(*) FROM trace_parity_audit";

    static final String INSERT_SQL = "INSERT INTO trace_segment "
            + "(trace_id, segment_id, endpoint, start_time, end_time, latency, is_error, payload_id, time_bucket) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    static final String SELECT_ALL_SQL = "SELECT trace_id, payload_id FROM trace_segment ORDER BY start_time ASC";

    static final String SELECT_TRACE_SQL = "SELECT payload_id FROM trace_segment WHERE trace_id = ? ORDER BY start_time ASC";

    static final String SELECT_RECENT_SQL = "SELECT trace_id, segment_id, endpoint, start_time, latency, is_error, payload_id "
            + "FROM trace_segment ORDER BY id DESC LIMIT ?";

    /**
     * 慢查询：按 endpoint + 最小耗时阈值取段，按 latency 降序。段级 latency（段内 maxEnd-minStart）。
     */
    static final String SELECT_SLOW_SQL = "SELECT trace_id, segment_id, endpoint, start_time, latency, is_error, payload_id "
            + "FROM trace_segment WHERE endpoint = ? AND latency >= ? ORDER BY latency DESC LIMIT ?";

    static final String COUNT_DISTINCT_SQL = "SELECT COUNT(DISTINCT trace_id) FROM trace_segment";

    static final String MAX_ID_SQL = "SELECT MAX(id) FROM trace_segment";

    static final String DELETE_CAP_SQL = "DELETE FROM trace_segment WHERE id <= ?";

    static final String DELETE_ALL_SEGMENTS_SQL = "DELETE FROM trace_segment";

    static final String DELETE_ALL_AUDITS_SQL = "DELETE FROM trace_parity_audit";

    // ==================== Phase 5：Trace 指标（分钟 + 小时，双分辨率同结构） ====================

    static final String CREATE_METRICS_MINUTE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS trace_metrics_minute ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
            + "endpoint VARCHAR(512) NOT NULL, "
            + "time_bucket BIGINT NOT NULL, "
            + "request_count BIGINT NOT NULL, "
            + "error_count BIGINT NOT NULL, "
            + "slow_count BIGINT NOT NULL, "
            + "total_latency BIGINT NOT NULL, "
            + "max_latency BIGINT, "
            + "p50 INT, p90 INT, p95 INT, p99 INT, "
            + "sample_count INT, "
            + "create_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
            + "update_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
            + ")";

    static final String CREATE_METRICS_MINUTE_KEY_INDEX_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_metrics_minute_key ON trace_metrics_minute(endpoint, time_bucket)";

    static final String CREATE_METRICS_MINUTE_BUCKET_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS ix_metrics_minute_bucket ON trace_metrics_minute(time_bucket)";

    static final String CREATE_METRICS_HOUR_TABLE_SQL = "CREATE TABLE IF NOT EXISTS trace_metrics_hour ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
            + "endpoint VARCHAR(512) NOT NULL, "
            + "time_bucket BIGINT NOT NULL, "
            + "request_count BIGINT NOT NULL, "
            + "error_count BIGINT NOT NULL, "
            + "slow_count BIGINT NOT NULL, "
            + "total_latency BIGINT NOT NULL, "
            + "max_latency BIGINT, "
            + "p50 INT, p90 INT, p95 INT, p99 INT, "
            + "sample_count INT, "
            + "create_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
            + "update_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
            + ")";

    static final String CREATE_METRICS_HOUR_KEY_INDEX_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_metrics_hour_key ON trace_metrics_hour(endpoint, time_bucket)";

    static final String CREATE_METRICS_HOUR_BUCKET_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS ix_metrics_hour_bucket ON trace_metrics_hour(time_bucket)";

    private static final String MERGE_METRICS_COLUMNS =
            "(endpoint, time_bucket, request_count, error_count, slow_count, total_latency, "
                    + "max_latency, p50, p90, p95, p99, sample_count, update_at) "
                    + "KEY(endpoint, time_bucket) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";

    static final String MERGE_METRICS_MINUTE_SQL = "MERGE INTO trace_metrics_minute " + MERGE_METRICS_COLUMNS;

    static final String MERGE_METRICS_HOUR_SQL = "MERGE INTO trace_metrics_hour " + MERGE_METRICS_COLUMNS;

    private static final String METRICS_SELECT_COLUMNS =
            "endpoint, time_bucket, request_count, error_count, slow_count, total_latency, "
                    + "max_latency, p50, p90, p95, p99, sample_count";

    static final String SELECT_METRICS_MINUTE_BY_ENDPOINT_SQL = "SELECT " + METRICS_SELECT_COLUMNS
            + " FROM trace_metrics_minute WHERE endpoint = ? AND time_bucket BETWEEN ? AND ? "
            + "ORDER BY time_bucket ASC LIMIT ?";

    static final String SELECT_METRICS_MINUTE_RANGE_SQL = "SELECT " + METRICS_SELECT_COLUMNS
            + " FROM trace_metrics_minute WHERE time_bucket BETWEEN ? AND ? "
            + "ORDER BY endpoint ASC, time_bucket ASC LIMIT ?";

    static final String SELECT_METRICS_HOUR_BY_ENDPOINT_SQL = "SELECT " + METRICS_SELECT_COLUMNS
            + " FROM trace_metrics_hour WHERE endpoint = ? AND time_bucket BETWEEN ? AND ? "
            + "ORDER BY time_bucket ASC LIMIT ?";

    static final String SELECT_METRICS_HOUR_RANGE_SQL = "SELECT " + METRICS_SELECT_COLUMNS
            + " FROM trace_metrics_hour WHERE time_bucket BETWEEN ? AND ? "
            + "ORDER BY endpoint ASC, time_bucket ASC LIMIT ?";

    /**
     * 按 endpoint 聚合的列（每端点一行，用于表格）：计数/总耗时/最大耗时精确求和取最大；
     * 分位按 request_count 加权平均（与 rollup 的近似口径一致）；{@code time_bucket} 常量 0 占位。
     * <p>
     * 另出"最差分钟"口径：{@code worst_pXX} = 各桶该分位的 {@code MAX}（NULL 忽略）；
     * {@code bucket_count} = 有数据（{@code request_count>0}）的桶数。
     * </p>
     */
    private static final String METRICS_AGG_COLUMNS =
            "endpoint, 0 AS time_bucket, "
                    + "SUM(request_count) AS request_count, "
                    + "SUM(error_count) AS error_count, "
                    + "SUM(slow_count) AS slow_count, "
                    + "SUM(total_latency) AS total_latency, "
                    + "MAX(max_latency) AS max_latency, "
                    + "CAST(ROUND(SUM(p50 * request_count) * 1.0 / NULLIF(SUM(CASE WHEN p50 IS NULL THEN 0 ELSE request_count END), 0)) AS INT) AS p50, "
                    + "CAST(ROUND(SUM(p90 * request_count) * 1.0 / NULLIF(SUM(CASE WHEN p90 IS NULL THEN 0 ELSE request_count END), 0)) AS INT) AS p90, "
                    + "CAST(ROUND(SUM(p95 * request_count) * 1.0 / NULLIF(SUM(CASE WHEN p95 IS NULL THEN 0 ELSE request_count END), 0)) AS INT) AS p95, "
                    + "CAST(ROUND(SUM(p99 * request_count) * 1.0 / NULLIF(SUM(CASE WHEN p99 IS NULL THEN 0 ELSE request_count END), 0)) AS INT) AS p99, "
                    + "SUM(sample_count) AS sample_count, "
                    + "MAX(p50) AS worst_p50, MAX(p90) AS worst_p90, MAX(p95) AS worst_p95, MAX(p99) AS worst_p99, "
                    + "SUM(CASE WHEN request_count > 0 THEN 1 ELSE 0 END) AS bucket_count";

    static final String SELECT_METRICS_MINUTE_AGG_SQL = "SELECT " + METRICS_AGG_COLUMNS
            + " FROM trace_metrics_minute WHERE time_bucket BETWEEN ? AND ? "
            + "GROUP BY endpoint ORDER BY SUM(request_count) DESC LIMIT ?";

    static final String SELECT_METRICS_HOUR_AGG_SQL = "SELECT " + METRICS_AGG_COLUMNS
            + " FROM trace_metrics_hour WHERE time_bucket BETWEEN ? AND ? "
            + "GROUP BY endpoint ORDER BY SUM(request_count) DESC LIMIT ?";

    static final String DELETE_METRICS_MINUTE_BEFORE_SQL = "DELETE FROM trace_metrics_minute WHERE time_bucket < ?";

    static final String DELETE_METRICS_HOUR_BEFORE_SQL = "DELETE FROM trace_metrics_hour WHERE time_bucket < ?";

    /** 范围内每个 endpoint 的"有数据桶数"（request_count>0），供活跃平均 QPS 分母。 */
    static final String SELECT_ACTIVE_BUCKETS_MINUTE_SQL = "SELECT endpoint, COUNT(DISTINCT time_bucket) AS active "
            + "FROM trace_metrics_minute WHERE request_count > 0 AND time_bucket BETWEEN ? AND ? GROUP BY endpoint";

    static final String SELECT_ACTIVE_BUCKETS_HOUR_SQL = "SELECT endpoint, COUNT(DISTINCT time_bucket) AS active "
            + "FROM trace_metrics_hour WHERE request_count > 0 AND time_bucket BETWEEN ? AND ? GROUP BY endpoint";

    /** 范围内 H2 已落库的桶集合：供聚合读口剔除内存中与已翻转账重叠的桶，避免重复计数。 */
    static final String SELECT_METRICS_MINUTE_BUCKETS_SQL =
            "SELECT DISTINCT time_bucket FROM trace_metrics_minute WHERE time_bucket BETWEEN ? AND ?";

    static final String SELECT_METRICS_HOUR_BUCKETS_SQL =
            "SELECT DISTINCT time_bucket FROM trace_metrics_hour WHERE time_bucket BETWEEN ? AND ?";

    static final String DELETE_ALL_METRICS_MINUTE_SQL = "DELETE FROM trace_metrics_minute";

    static final String DELETE_ALL_METRICS_HOUR_SQL = "DELETE FROM trace_metrics_hour";

    private H2SqlStatements() {
    }
}
