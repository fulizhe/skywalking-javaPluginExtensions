package org.apache.skywalking.apm.agent.core.reporter.logfile.storage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.reporter.logfile.Log;
import org.apache.skywalking.apm.agent.core.reporter.logfile.SegmentLogConverter;
import org.apache.skywalking.apm.agent.core.reporter.logfile.TraceParityComparator;
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

    private static final int AUDIT_WATER_LEVEL = 1000;

    private static final Gson GSON = new Gson();

    private final boolean enabled;
    private final int shadowMaxRows;
    private Connection connection;
    /** 载荷环形文件（可为 null：capped 未启用或初始化失败） */
    private final CappedFileStorage cappedStorage;
    private final AtomicLong errorCount = new AtomicLong(0);

    /** 异步写队列（启用时非空）；满则丢弃并计数，不阻塞业务线程。 */
    private final BlockingQueue<TraceSegment> writeQueue;
    private volatile boolean writerRunning;
    private Thread writerThread;
    private final AtomicLong writeQueueDropped = new AtomicLong(0);
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private static final int WRITE_QUEUE_CAPACITY = 4096;
    private long lastErrorLogTime = 0;
    private static final long ERROR_LOG_INTERVAL_MS = 30_000L;

    /** H2 Web Console（可选，默认关闭；shade 重定位后静态资源需实测） */
    private Server consoleServer;

    /**
     * 用配置创建实例（capped 载荷文件关闭；仅供测试/兼容）。
     *
     * @param enabled       是否启用
     * @param shadowMaxRows 行数水位上限
     */
    public H2TraceSegmentStorage(final boolean enabled, final int shadowMaxRows) {
        this(enabled, shadowMaxRows, false, null, 0L);
    }

    /**
     * 用配置创建实例。
     *
     * @param enabled        是否启用
     * @param shadowMaxRows  行数水位上限
     * @param cappedEnabled  是否启用环形载荷文件
     * @param cappedFile     环形文件路径（cappedEnabled 时必填）
     * @param cappedSizeBytes 环形文件固定大小（字节）
     */
    public H2TraceSegmentStorage(final boolean enabled, final int shadowMaxRows,
            final boolean cappedEnabled, final File cappedFile, final long cappedSizeBytes) {
        this.enabled = enabled;
        this.shadowMaxRows = shadowMaxRows > 0 ? shadowMaxRows : 2000;
        CappedFileStorage capped = null;
        Connection conn = null;
        if (enabled) {
            if (cappedEnabled && cappedFile != null && cappedSizeBytes > 0) {
                try {
                    capped = new CappedFileStorage(cappedFile, cappedSizeBytes);
                    LOGGER.info("### [H2Shadow] CappedFileStorage initialized: file={}, sizeBytes={}", cappedFile, cappedSizeBytes);
                } catch (IOException e) {
                    recordError("cappedInit", e);
                }
            }
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
                    stmt.execute(H2SqlStatements.CREATE_TABLE_SQL);
                    stmt.execute(H2SqlStatements.CREATE_INDEX_SQL);
                    stmt.execute(H2SqlStatements.CREATE_AUDIT_TABLE_SQL);
                }
                LOGGER.info("### [H2Shadow] H2TraceSegmentStorage initialized: url={}, shadowMaxRows={}", JDBC_URL, this.shadowMaxRows);
            } catch (SQLException e) {
                conn = safeClose(conn);
                recordError("init", e);
            }
        }
        this.cappedStorage = capped;
        this.connection = conn;
        if (enabled && conn != null) {
            this.writeQueue = new ArrayBlockingQueue<TraceSegment>(WRITE_QUEUE_CAPACITY);
            this.writerRunning = true;
            final Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    drainLoop();
                }
            }, "H2Shadow-Writer");
            t.setDaemon(true);
            this.writerThread = t;
            t.start();
        } else {
            this.writeQueue = null;
        }
    }

    @Override
    public void accept(final List<TraceSegment> segments) {
        if (!enabled || connection == null || segments == null || segments.isEmpty()) {
            return;
        }
        final BlockingQueue<TraceSegment> q = writeQueue;
        if (q == null) {
            // 异步不可用：退化为同步（不阻塞承诺仅在队列可用时成立）
            for (TraceSegment segment : segments) {
                storeSegment(segment);
            }
            return;
        }
        for (TraceSegment segment : segments) {
            if (segment == null || segment.isIgnore()) {
                continue;
            }
            if (!q.offer(segment)) {
                writeQueueDropped.incrementAndGet();
            }
        }
    }

    /** 单段转换 + 落库（写线程调用；测试可经 storeLog 直接调用）。 */
    private void storeSegment(final TraceSegment segment) {
        if (segment == null || segment.isIgnore()) {
            return;
        }
        try {
            final SegmentObject segmentObject = segment.transform();
            final Log log = SegmentLogConverter.toLog(segmentObject);
            storeLog(log);
        } catch (Exception e) {
            recordError("accept", e);
        }
    }

    /** 独立写线程主循环：排空队列；close 置 writerRunning=false 后收尾排空。 */
    private void drainLoop() {
        final BlockingQueue<TraceSegment> q = writeQueue;
        while (writerRunning) {
            try {
                final TraceSegment seg = q.poll(200L, TimeUnit.MILLISECONDS);
                if (seg != null) {
                    inFlight.incrementAndGet();
                    try {
                        storeSegment(seg);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        TraceSegment seg;
        while ((seg = q.poll()) != null) {
            inFlight.incrementAndGet();
            try {
                storeSegment(seg);
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    /**
     * 等待异步写队列排空且当前在写的段落库完成（供对账在快照前调用，消除异步竞态）。
     *
     * @return 是否在超时前空闲
     */
    public boolean awaitIdle(final long timeoutMs) {
        final BlockingQueue<TraceSegment> q = writeQueue;
        if (q == null) {
            return true;
        }
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (q.isEmpty() && inFlight.get() == 0) {
                return true;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return q.isEmpty() && inFlight.get() == 0;
    }

    /** 异步写队列因满而丢弃的段数。 */
    public long getWriteQueueDropped() {
        return writeQueueDropped.get();
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

                // 载荷先写环形文件（GZIP），再把指针写入 H2；写失败则 payload_id 置空。
                long payloadId = -1L;
                if (cappedStorage != null) {
                    try {
                        payloadId = cappedStorage.writeMessage(GSON.toJson(log.toMap()).getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        recordError("cappedWrite", e);
                    }
                }

                try (PreparedStatement ps = connection.prepareStatement(H2SqlStatements.INSERT_SQL)) {
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
                    if (payloadId >= 0) {
                        ps.setLong(11, payloadId);
                    } else {
                        ps.setNull(11, Types.BIGINT);
                    }
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
                    ResultSet rs = stmt.executeQuery(H2SqlStatements.SELECT_ALL_SQL)) {
                while (rs.next()) {
                    final String traceId = rs.getString("trace_id");
                    if (traceId == null) {
                        continue;
                    }
                    final long payloadId = rs.getLong("payload_id");
                    final boolean payloadNull = rs.wasNull();
                    Map<String, Object> logMap = null;
                    if (!payloadNull && payloadId >= 0 && cappedStorage != null) {
                        try {
                            final byte[] bytes = cappedStorage.readMessage(payloadId);
                            if (bytes != null) {
                                logMap = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8),
                                        new TypeToken<Map<String, Object>>(){}.getType());
                            }
                        } catch (IOException e) {
                            recordError("snapshot", e);
                        }
                    }
                    if (logMap == null) {
                        // payload 缺失或已过期（header 仍在）：跳过该 payload，不脏读
                        continue;
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
                    ResultSet rs = stmt.executeQuery(H2SqlStatements.COUNT_DISTINCT_SQL)) {
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
     * 按 traceId 取回整条链路（与旧 {@code data[traceId].logs} 同契约）。
     * <p>
     * 命中：返回 {@code {logs:[...], payloadExpired:boolean}}；不存在：{@code logs} 为空；
     * payload 已过期时该段不出现、{@code payloadExpired=true}（header 语义保留）。
     * </p>
     */
    public Map<String, Object> queryTrace(final String traceId) {
        final Map<String, Object> result = new LinkedHashMap<String, Object>();
        final List<Map<String, Object>> logs = new ArrayList<Map<String, Object>>();
        boolean anyExpired = false;
        if (connection == null || traceId == null) {
            result.put("logs", logs);
            result.put("payloadExpired", false);
            return result;
        }
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(H2SqlStatements.SELECT_TRACE_SQL)) {
                ps.setString(1, traceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final long payloadId = rs.getLong("payload_id");
                        final boolean payloadNull = rs.wasNull();
                        if (payloadNull || payloadId < 0 || cappedStorage == null) {
                            continue;
                        }
                        if (cappedStorage.isOverwritten(payloadId)) {
                            anyExpired = true;
                            continue;
                        }
                        try {
                            final byte[] bytes = cappedStorage.readMessage(payloadId);
                            if (bytes == null) {
                                anyExpired = true;
                                continue;
                            }
                            final Map<String, Object> logMap = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8),
                                    new TypeToken<Map<String, Object>>(){}.getType());
                            if (logMap != null) {
                                logs.add(logMap);
                            }
                        } catch (IOException e) {
                            recordError("queryTrace", e);
                        }
                    }
                }
            } catch (SQLException e) {
                recordError("queryTrace", e);
            }
        }
        result.put("logs", logs);
        result.put("payloadExpired", anyExpired);
        return result;
    }

    /**
     * 最近 N 条 segment header（供挑选 traceId）；{@code payloadExpired} 标记 payload 是否已过期。
     */
    public List<Map<String, Object>> recentTraces(final int limit) {
        final List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (connection == null || limit <= 0) {
            return out;
        }
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(H2SqlStatements.SELECT_RECENT_SQL)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final Map<String, Object> row = new LinkedHashMap<String, Object>();
                        row.put("traceId", rs.getString("trace_id"));
                        row.put("traceSegmentId", rs.getString("segment_id"));
                        row.put("service", rs.getString("service"));
                        row.put("endpoint", rs.getString("endpoint"));
                        row.put("startTime", rs.getLong("start_time"));
                        row.put("latency", rs.getInt("latency"));
                        row.put("isError", rs.getBoolean("is_error"));
                        final long payloadId = rs.getLong("payload_id");
                        final boolean payloadNull = rs.wasNull();
                        final boolean hasPayload = !payloadNull && payloadId >= 0;
                        row.put("hasPayload", hasPayload);
                        row.put("payloadExpired", hasPayload && cappedStorage != null
                                && cappedStorage.isOverwritten(payloadId));
                        out.add(row);
                    }
                }
            } catch (SQLException e) {
                recordError("recentTraces", e);
            }
        }
        return out;
    }

    /**
     * 行数水位上限：超过 {@link #shadowMaxRows} 后按 {@code id} 水位节流清理。
     */
    private void enforceRowCap() {
        if (shadowMaxRows <= 0) {
            return;
        }
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(H2SqlStatements.MAX_ID_SQL)) {
            if (rs.next()) {
                final long maxId = rs.getLong(1);
                if (maxId > shadowMaxRows) {
                    final long threshold = maxId - shadowMaxRows;
                    try (PreparedStatement ps = connection.prepareStatement(H2SqlStatements.DELETE_CAP_SQL)) {
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
                stmt.execute(H2SqlStatements.DELETE_ALL_SEGMENTS_SQL);
                stmt.execute(H2SqlStatements.DELETE_ALL_AUDITS_SQL);
            } catch (SQLException e) {
                recordError("clear", e);
            }
        }
    }

    /**
     * 写入对账差异到审计表 {@code trace_parity_audit}（水位上限 1000，临时表）。
     * 包级可见，供 {@code LogFileTraceSegmentServiceClient} 对账触发器调用。
     */
    public void insertAuditRows(final List<TraceParityComparator.DiffEntry> diffs) {
        if (connection == null || diffs == null || diffs.isEmpty()) {
            return;
        }
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(H2SqlStatements.INSERT_AUDIT_SQL)) {
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
                ResultSet rs = stmt.executeQuery(H2SqlStatements.MAX_AUDIT_ID_SQL)) {
            if (rs.next()) {
                final long maxId = rs.getLong(1);
                if (maxId > AUDIT_WATER_LEVEL) {
                    final long threshold = maxId - AUDIT_WATER_LEVEL;
                    try (PreparedStatement ps = connection.prepareStatement(H2SqlStatements.DELETE_AUDIT_CAP_SQL)) {
                        ps.setLong(1, threshold);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            recordError("enforceAuditRowCap", e);
        }
    }

    /**
     * 读取审计表最近 {@code limit} 条差异（倒序），供 {@code statisticParity()} 展示。
     * 返回 JDK 原生 Map 列表；任何异常就地捕获并返回已读到的部分。
     */
    public List<Map<String, Object>> recentAuditRows(final int limit) {
        final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (connection == null || limit <= 0) {
            return rows;
        }
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(H2SqlStatements.SELECT_AUDIT_RECENT_SQL)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final Map<String, Object> row = new LinkedHashMap<String, Object>();
                        row.put("checkTime", rs.getString("check_time"));
                        row.put("traceId", rs.getString("trace_id"));
                        row.put("diffType", rs.getString("diff_type"));
                        row.put("expected", rs.getString("expected"));
                        row.put("actual", rs.getString("actual"));
                        row.put("detail", rs.getString("detail"));
                        rows.add(row);
                    }
                }
            } catch (SQLException e) {
                recordError("recentAuditRows", e);
            }
        }
        return rows;
    }

    /** 审计表当前行数（水位状态）。 */
    public int auditRowCount() {
        if (connection == null) {
            return 0;
        }
        synchronized (this) {
            try (Statement stmt = connection.createStatement();
                    ResultSet rs = stmt.executeQuery(H2SqlStatements.COUNT_AUDIT_SQL)) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                recordError("auditRowCount", e);
            }
            return 0;
        }
    }

    /** 审计表固定水位上限（Phase 1 临时表，不新增配置）。 */
    public int auditWaterLevel() {
        return AUDIT_WATER_LEVEL;
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
        writerRunning = false;
        final Thread t = writerThread;
        if (t != null) {
            try {
                t.join(5000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        final BlockingQueue<TraceSegment> q = writeQueue;
        if (q != null) {
            TraceSegment seg;
            while ((seg = q.poll()) != null) {
                storeSegment(seg);
            }
        }
        if (consoleServer != null) {
            try {
                consoleServer.stop();
            } catch (Exception ignored) {
            }
            consoleServer = null;
        }
        if (cappedStorage != null) {
            try {
                cappedStorage.close();
            } catch (IOException ignored) {
            }
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
