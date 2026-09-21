package org.apache.skywalking.apm.agent.core.reporter.logfile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.dependencies.com.google.gson.Gson;
import org.apache.skywalking.apm.dependencies.com.google.gson.reflect.TypeToken;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.h2.Driver;
import org.h2.tools.Server;

/**
 * H2 内存模式实现：影子写入 trace segment，与旧 {@code KeyedLocalStore} 路径并行双跑。
 * <p>
 * JDBC URL {@code jdbc:h2:mem:sw_trace_segment;DB_CLOSE_DELAY=-1}，不落盘、不产生磁盘文件。
 * 一条 segment 一行，{@code data_binary} 存该段 {@code Log.toMap()} JSON；
 * {@link #snapshot()} 按 {@code trace_id} 取行 + {@code start_time} 排序组装。
 * </p>
 * <p>
 * Phase 1 临时简化：同步批量 insert + 行数水位上限（{@code shadow_max_rows}）。
 * <b>file 阶段必须切换为独立写线程</b>（统一方案 §6.1 B）。
 * </p>
 * <p>
 * 所有异常就地捕获（计数 + 限速日志），绝不向上抛——"监控只能是助力，不是阻碍"。
 * </p>
 */
public class H2TraceSegmentStorage implements TraceSegmentStorage {

    private static final ILog LOGGER = LogManager.getLogger(H2TraceSegmentStorage.class);

    private static final String JDBC_URL = "jdbc:h2:mem:sw_trace_segment;DB_CLOSE_DELAY=-1";
    private static final String JDBC_USER = "sa";
    private static final String JDBC_PASSWORD = "";

    private static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS trace_segment ("
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
            + "data_binary MEDIUMTEXT, "
            + "time_bucket BIGINT NOT NULL, "
            + "create_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
            + ")";

    private static final String CREATE_INDEX_SQL = "CREATE INDEX IF NOT EXISTS idx_trace_segment_trace_id ON trace_segment(trace_id)";

    private static final String CREATE_AUDIT_TABLE_SQL = "CREATE TABLE IF NOT EXISTS trace_parity_audit ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
            + "check_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
            + "trace_id VARCHAR(128), "
            + "diff_type VARCHAR(32), "
            + "expected VARCHAR(2048), "
            + "actual VARCHAR(2048), "
            + "detail VARCHAR(2048)"
            + ")";

    private static final String INSERT_AUDIT_SQL = "INSERT INTO trace_parity_audit (trace_id, diff_type, expected, actual, detail) VALUES (?, ?, ?, ?, ?)";

    private static final String MAX_AUDIT_ID_SQL = "SELECT MAX(id) FROM trace_parity_audit";

    private static final String DELETE_AUDIT_CAP_SQL = "DELETE FROM trace_parity_audit WHERE id <= ?";

    private static final int AUDIT_WATER_LEVEL = 1000;

    private static final String INSERT_SQL = "INSERT INTO trace_segment "
            + "(trace_id, segment_id, service, service_instance, endpoint, start_time, end_time, latency, is_error, trace_level, data_binary, time_bucket) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_ALL_SQL = "SELECT trace_id, data_binary FROM trace_segment ORDER BY start_time ASC";

    private static final String COUNT_DISTINCT_SQL = "SELECT COUNT(DISTINCT trace_id) FROM trace_segment";

    private static final String MAX_ID_SQL = "SELECT MAX(id) FROM trace_segment";

    private static final String DELETE_CAP_SQL = "DELETE FROM trace_segment WHERE id <= ?";

    private static final Gson GSON = new Gson();

    private final boolean enabled;
    private final int shadowMaxRows;
    private Connection connection;
    private final AtomicLong errorCount = new AtomicLong(0);
    private long lastErrorLogTime = 0;
    private static final long ERROR_LOG_INTERVAL_MS = 30_000L;

    /** H2 Web Console（可选，默认关闭；shade 重定位后静态资源需实测） */
    private Server consoleServer;

    /**
     * 用配置创建实例。
     *
     * @param enabled       是否启用
     * @param shadowMaxRows 行数水位上限
     */
    public H2TraceSegmentStorage(final boolean enabled, final int shadowMaxRows) {
        this.enabled = enabled;
        this.shadowMaxRows = shadowMaxRows > 0 ? shadowMaxRows : 2000;
        Connection conn = null;
        if (enabled) {
            try {
                // SkyWalking PluginClassLoader 不暴露 META-INF/services 给 DriverManager 的 ServiceLoader，
                // 必须显式加载并注册 shaded Driver 类（shade 重定位 org.h2.Driver → org.apache.skywalking.apm.dependencies.h2.Driver）。
                final Driver h2Driver = new Driver();
                DriverManager.registerDriver(h2Driver);

                final Properties props = new Properties();
                props.setProperty("user", JDBC_USER);
                props.setProperty("password", JDBC_PASSWORD);
                conn = h2Driver.connect(JDBC_URL, props);
                if (conn == null) {
                    throw new SQLException("H2 Driver.connect returned null for URL: " + JDBC_URL);
                }
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(CREATE_TABLE_SQL);
                    stmt.execute(CREATE_INDEX_SQL);
                    stmt.execute(CREATE_AUDIT_TABLE_SQL);
                }
                LOGGER.info("### [H2Shadow] H2TraceSegmentStorage initialized: url={}, shadowMaxRows={}", JDBC_URL, this.shadowMaxRows);
            } catch (SQLException e) {
                conn = safeClose(conn);
                recordError("init", e);
            }
        }
        this.connection = conn;
    }

    @Override
    public void accept(final List<TraceSegment> segments) {
        if (!enabled || connection == null || segments == null || segments.isEmpty()) {
            return;
        }
        for (TraceSegment segment : segments) {
            if (segment == null || segment.isIgnore()) {
                continue;
            }
            try {
                final SegmentObject segmentObject = segment.transform();
                final Log log = SegmentLogConverter.toLog(segmentObject);
                storeLog(log);
            } catch (Exception e) {
                recordError("accept", e);
            }
        }
    }

    /**
     * 存储（或更新）一条 segment。包级可见，供单元测试直接构造 {@link Log} 调用。
     * <p>
     * 同步批量 insert（Phase 1 临时简化）。
     * </p>
     */
    void storeLog(final Log log) {
        if (!enabled || connection == null || log == null) {
            return;
        }
        synchronized (this) {
            try {
                final List<Log.SpanInfo> spans = log.getSpans();
                long startTime = Long.MAX_VALUE;
                long endTime = Long.MIN_VALUE;
                boolean isError = false;
                String endpoint = "";
                if (spans != null) {
                    for (Log.SpanInfo span : spans) {
                        if (span.getStartTime() < startTime) {
                            startTime = span.getStartTime();
                        }
                        if (span.getEndTime() > endTime) {
                            endTime = span.getEndTime();
                        }
                        if (span.getIsError()) {
                            isError = true;
                        }
                        if (endpoint.isEmpty() && "Entry".equals(span.getSpanType())) {
                            endpoint = span.getOperationName();
                        }
                    }
                }
                if (startTime == Long.MAX_VALUE) {
                    startTime = System.currentTimeMillis();
                }
                if (endTime == Long.MIN_VALUE) {
                    endTime = startTime;
                }
                final int latency = (int) (endTime - startTime);
                final long timeBucket = startTime / 60_000L;
                final String dataBinary = GSON.toJson(log.toMap());

                try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
                    ps.setString(1, log.getTraceId());
                    ps.setString(2, log.getTraceSegmentId());
                    ps.setString(3, log.getService());
                    ps.setString(4, log.getServiceInstance());
                    ps.setString(5, endpoint);
                    ps.setLong(6, startTime);
                    ps.setLong(7, endTime);
                    ps.setInt(8, latency);
                    ps.setBoolean(9, isError);
                    ps.setString(10, null); // trace_level: Phase 1 可空
                    ps.setString(11, dataBinary);
                    ps.setLong(12, timeBucket);
                    ps.executeUpdate();
                }
                enforceRowCap();
            } catch (Exception e) {
                recordError("storeLog", e);
            }
        }
    }

    @Override
    public Map<String, Map<String, Object>> snapshot() {
        if (!enabled || connection == null) {
            return new LinkedHashMap<String, Map<String, Object>>();
        }
        synchronized (this) {
            // traceId → list of log maps, in start_time order
            final Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<String, List<Map<String, Object>>>();
            try (Statement stmt = connection.createStatement();
                    ResultSet rs = stmt.executeQuery(SELECT_ALL_SQL)) {
                while (rs.next()) {
                    final String traceId = rs.getString("trace_id");
                    final String dataBinary = rs.getString("data_binary");
                    if (traceId == null || dataBinary == null) {
                        continue;
                    }
                    Map<String, Object> logMap = GSON.fromJson(dataBinary, new TypeToken<Map<String, Object>>(){}.getType());
                    if (logMap == null) {
                        logMap = new LinkedHashMap<String, Object>();
                    }
                    List<Map<String, Object>> logs = grouped.get(traceId);
                    if (logs == null) {
                        logs = new ArrayList<Map<String, Object>>();
                        grouped.put(traceId, logs);
                    }
                    logs.add(logMap);
                }
            } catch (SQLException e) {
                recordError("snapshot", e);
                return new LinkedHashMap<String, Map<String, Object>>();
            }
            // assemble: traceId → { "logs": [logMap, ...] }
            final Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
            for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
                final Map<String, Object> traceData = new LinkedHashMap<String, Object>();
                traceData.put("logs", entry.getValue());
                result.put(entry.getKey(), traceData);
            }
            return result;
        }
    }

    @Override
    public int size() {
        if (!enabled || connection == null) {
            return 0;
        }
        synchronized (this) {
            try (Statement stmt = connection.createStatement();
                    ResultSet rs = stmt.executeQuery(COUNT_DISTINCT_SQL)) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                recordError("size", e);
            }
            return 0;
        }
    }

    /**
     * 行数水位上限：超过 {@link #shadowMaxRows} 后按 {@code id} 水位节流清理。
     */
    private void enforceRowCap() {
        if (shadowMaxRows <= 0) {
            return;
        }
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(MAX_ID_SQL)) {
            if (rs.next()) {
                final long maxId = rs.getLong(1);
                if (maxId > shadowMaxRows) {
                    final long threshold = maxId - shadowMaxRows;
                    try (PreparedStatement ps = connection.prepareStatement(DELETE_CAP_SQL)) {
                        ps.setLong(1, threshold);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            recordError("enforceRowCap", e);
        }
    }

    /**
     * 异常计数 + 限速日志（同类操作 30s 最多一条），绝不外抛。
     */
    private void recordError(final String operation, final Throwable t) {
        final long count = errorCount.incrementAndGet();
        final long now = System.currentTimeMillis();
        if (now - lastErrorLogTime > ERROR_LOG_INTERVAL_MS) {
            lastErrorLogTime = now;
            LOGGER.error(t, "### [H2Shadow] {} error (total errors: {})", operation, count);
        }
    }

    /**
     * 清空所有行（包级可见，供单元测试隔离使用）。
     */
    void clear() {
        if (connection == null) {
            return;
        }
        synchronized (this) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DELETE FROM trace_segment");
                stmt.execute("DELETE FROM trace_parity_audit");
            } catch (SQLException e) {
                recordError("clear", e);
            }
        }
    }

    /**
     * 写入对账差异到审计表 {@code trace_parity_audit}（水位上限 1000，临时表）。
     * 包级可见，供 {@code LogFileTraceSegmentServiceClient} 对账触发器调用。
     */
    void insertAuditRows(final List<TraceParityComparator.DiffEntry> diffs) {
        if (connection == null || diffs == null || diffs.isEmpty()) {
            return;
        }
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(INSERT_AUDIT_SQL)) {
                for (TraceParityComparator.DiffEntry diff : diffs) {
                    ps.setString(1, diff.getTraceId());
                    ps.setString(2, diff.getType().name());
                    ps.setString(3, truncate(diff.getExpected(), 2048));
                    ps.setString(4, truncate(diff.getActual(), 2048));
                    ps.setString(5, truncate(diff.getDetail(), 2048));
                    ps.addBatch();
                }
                ps.executeBatch();
                enforceAuditRowCap();
            } catch (SQLException e) {
                recordError("insertAuditRows", e);
            }
        }
    }

    private void enforceAuditRowCap() {
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(MAX_AUDIT_ID_SQL)) {
            if (rs.next()) {
                final long maxId = rs.getLong(1);
                if (maxId > AUDIT_WATER_LEVEL) {
                    final long threshold = maxId - AUDIT_WATER_LEVEL;
                    try (PreparedStatement ps = connection.prepareStatement(DELETE_AUDIT_CAP_SQL)) {
                        ps.setLong(1, threshold);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            recordError("enforceAuditRowCap", e);
        }
    }

    private static String truncate(final String s, final int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    /**
     * 启动 H2 Web Console（浏览器访问 {@code http://<host>:<port>}，连接信息：
     * JDBC URL {@code jdbc:h2:mem:sw_trace_segment;DB_CLOSE_DELAY=-1}，User {@code sa}，密码空）。
     * <p>
     * ⚠️ 控制台可对库执行任意 SQL，仅测试环境开启、用完即关。
     * shade 重定位后静态资源路径需实测；不可用则退路为 TCP Server + 外部客户端。
     * </p>
     */
    public void startConsole(final int port) {
        if (connection == null) {
            return;
        }
        try {
            consoleServer = Server.createWebServer(
                    "-web", "-webAllowOthers", "-webPort", String.valueOf(port)
            ).start();
            LOGGER.info("### [H2Shadow] Web Console started on port {} (URL: jdbc:h2:mem:sw_trace_segment;DB_CLOSE_DELAY=-1, user: sa, pass: <empty>)", port);
        } catch (Exception e) {
            recordError("startConsole", e);
        }
    }

    /**
     * 关闭连接与控制台（供 shutdown 调用）。
     */
    public void close() {
        if (consoleServer != null) {
            try {
                consoleServer.stop();
            } catch (Exception ignored) {
            }
            consoleServer = null;
        }
        connection = safeClose(connection);
    }

    private static Connection safeClose(final Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
        }
        return null;
    }

    /**
     * 错误计数（供调试 / 审计读取）。
     */
    public long getErrorCount() {
        return errorCount.get();
    }
}
