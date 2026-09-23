package org.apache.skywalking.apm.agent.core.reporter.logfile.storage;

final class H2SqlStatements {

    static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS trace_segment ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
            + "trace_id VARCHAR(128) NOT NULL, "
            + "segment_id VARCHAR(128) NOT NULL, "
            + "service VARCHAR(256), "
            + "service_instance VARCHAR(256), "
            + "endpoint VARCHAR(512), "
            + "start_time BIGINT NOT NULL, "
            + "end_time BIGINT NOT NULL, "
            + "latency INT NOT NULL, "
            + "is_error BOOLEAN NOT NULL, "
            + "trace_level VARCHAR(16), "
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
            + "(trace_id, segment_id, service, service_instance, endpoint, start_time, end_time, latency, is_error, trace_level, payload_id, time_bucket) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    static final String SELECT_ALL_SQL = "SELECT trace_id, payload_id FROM trace_segment ORDER BY start_time ASC";

    static final String SELECT_TRACE_SQL = "SELECT payload_id FROM trace_segment WHERE trace_id = ? ORDER BY start_time ASC";

    static final String SELECT_RECENT_SQL = "SELECT trace_id, segment_id, service, endpoint, start_time, latency, is_error, payload_id "
            + "FROM trace_segment ORDER BY id DESC LIMIT ?";

    static final String COUNT_DISTINCT_SQL = "SELECT COUNT(DISTINCT trace_id) FROM trace_segment";

    static final String MAX_ID_SQL = "SELECT MAX(id) FROM trace_segment";

    static final String DELETE_CAP_SQL = "DELETE FROM trace_segment WHERE id <= ?";

    static final String DELETE_ALL_SEGMENTS_SQL = "DELETE FROM trace_segment";

    static final String DELETE_ALL_AUDITS_SQL = "DELETE FROM trace_parity_audit";

    private H2SqlStatements() {
    }
}
