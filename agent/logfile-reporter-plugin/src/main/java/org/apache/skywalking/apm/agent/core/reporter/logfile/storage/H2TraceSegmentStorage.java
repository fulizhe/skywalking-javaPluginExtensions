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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
import org.apache.skywalking.apm.agent.core.reporter.logfile.metrics.MetricsRow;
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

    /**
     * {@code trace_segment} 单行堆成本估算（字节/行），用于把行数折成"约多少 MB"。
     * <p>
     * 取自实测 {@code docs/notes/2026-09-27-h2-mem-capacity-estimate.md} §7.1/§7.3：
     * 同实例两点差分 {@code Δheap 18.07 MB ÷ Δrows 23,587} ≈ 804 B/行（偏上估计，含 metrics 行增长）。
     * 刻意取整成 800 并<b>在读口页面写明这是估算</b>——H2 2.x 不提供内存读口，真实字节数
     * 只能带外 {@code jmap -histo:live} 测；给一个标了口径的估算，胜过给一个假装精确的数字。
     * </p>
     */
    private static final long H2_BYTES_PER_ROW_ESTIMATE = 800L;

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
    /** 已成功写入 trace 表的行数；仅由写线程在 {@code synchronized(this)} 内自增，用于水位校验节流。 */
    private volatile long insertCount;
    /** 被行数水位清理掉的累计行数（与 {@link #insertCount} 相减即当前存活行数，避免读口做全表 COUNT）。 */
    private volatile long deletedByRowCap;

    // ==================== 交叉验证用的增量水位（供 /inner/sw/self-stat）====================
    // 目的：回答"持久层最老的一条是什么时候"以及"是丢了还是过期了"。
    // 这两件事都**不能**用 MIN(start_time) 直接问 —— start_time 无索引，那是全表扫
    // （10 万行实测中位 23 ms、p90 44 ms），而本类所有读写都在 synchronized(this) 里，
    // 一次这样的查询会把写线程按停几十毫秒。改为"主键 seek，每 1024 行刷新一次"。
    /**
     * 最老存活行（按 {@code id} 升序）的 {@code start_time}（毫秒）；MAX_VALUE = 持久层尚无行。
     *
     * <p><b>口径：按插入序最老的那行，不是严格 MIN(start_time)</b>。两者在乱序时会不同
     * （异步段、时钟回拨能让后写入的行带更早的 {@code start_time}），此时本值会略新。
     * 这是刻意的取舍：想拿严格 MIN 就得扫表（23 ms/次），而读口每 5s 轮询一次。
     * 曾经试过让"插入时增量 min"与 seek 结果取 min 来兼顾，<b>那样是错的</b>——
     * 增量 min 会被行数水位清掉而残留（它不带删除补偿），取 min 反而永远选那个已删值。
     * </p>
     */
    private volatile long oldestLiveStartTime = Long.MAX_VALUE;
    /** 最老存活行的 {@code payload_id}（环上的逻辑偏移）；MAX_VALUE = 尚无行，-1 = 该行没有载荷。 */
    private volatile long oldestLivePayloadId = Long.MAX_VALUE;
    /**
     * 全部插入过的行的 {@code start_time} 最大值；0 = 尚无行。
     * <p>删除只删最老行，所以最新行永远不会被删 —— 这个值<b>永不失效、无需刷新</b>，
     * 纯 insert 时取 max 即可。</p>
     */
    private volatile long newestStartTime;
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
        this.shadowMaxRows = shadowMaxRows > 0 ? shadowMaxRows : 100000;
        this.cappedStorage = initCappedStorage(cappedEnabled, cappedFile, cappedSizeBytes);
        this.connection = initConnection();
        if (enabled && connection != null) {
            this.writeQueue = new ArrayBlockingQueue<TraceSegment>(WRITE_QUEUE_CAPACITY);
            startWriter();
        } else {
            this.writeQueue = null;
        }
    }

    private CappedFileStorage initCappedStorage(final boolean cappedEnabled, final File cappedFile,
            final long cappedSizeBytes) {
        if (!enabled || !cappedEnabled || cappedFile == null || cappedSizeBytes <= 0) {
            return null;
        }
        try {
            final CappedFileStorage capped = new CappedFileStorage(cappedFile, cappedSizeBytes);
            LOGGER.info("### [H2Shadow] CappedFileStorage initialized: file={}, sizeBytes={}", cappedFile, cappedSizeBytes);
            return capped;
        } catch (IOException e) {
            recordError("cappedInit", e);
            return null;
        }
    }

    private Connection initConnection() {
        if (!enabled) {
            return null;
        }
        Connection conn = null;
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
                // mem 模式：先删再建，保证 schema 变更（删 trace_level）即时生效、无残留旧列
                stmt.execute(H2SqlStatements.DROP_SEGMENT_TABLE_SQL);
                stmt.execute(H2SqlStatements.CREATE_TABLE_SQL);
                stmt.execute(H2SqlStatements.CREATE_INDEX_SQL);
                stmt.execute(H2SqlStatements.CREATE_AUDIT_TABLE_SQL);
                // Phase 5：Trace 指标双分辨率表（分钟 + 小时）
                stmt.execute(H2SqlStatements.CREATE_METRICS_MINUTE_TABLE_SQL);
                stmt.execute(H2SqlStatements.CREATE_METRICS_MINUTE_KEY_INDEX_SQL);
                stmt.execute(H2SqlStatements.CREATE_METRICS_MINUTE_BUCKET_INDEX_SQL);
                stmt.execute(H2SqlStatements.CREATE_METRICS_HOUR_TABLE_SQL);
                stmt.execute(H2SqlStatements.CREATE_METRICS_HOUR_KEY_INDEX_SQL);
                stmt.execute(H2SqlStatements.CREATE_METRICS_HOUR_BUCKET_INDEX_SQL);
            }
            LOGGER.info("### [H2Shadow] H2TraceSegmentStorage initialized: url={}, shadowMaxRows={}", JDBC_URL, this.shadowMaxRows);
            return conn;
        } catch (SQLException e) {
            recordError("init", e);
            return safeClose(conn);
        }
    }

    private void startWriter() {
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

    private void drainQueue() {
        final BlockingQueue<TraceSegment> q = writeQueue;
        if (q == null) {
            return;
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

    /** trace 表行数水位上限（{@code h2.shadow_max_rows}）。 */
    public int getMaxRows() {
        return shadowMaxRows;
    }

    /** 异步写队列容量（有界背压的硬上限）。 */
    public int getWriteQueueCapacity() {
        return WRITE_QUEUE_CAPACITY;
    }

    /**
     * <b>未提交积压量</b>：已入队但尚未写进 H2 的段数 = 队列里待处理 + 正在写。
     * <p>
     * 这是"监控自己有没有被业务拖住"的核心数字：它持续上涨说明 H2 写入（含 payload 落盘）
     * 已经跟不上采集速度，再涨就会顶到队列容量并开始丢段（{@link #getWriteQueueDropped()}）。
     * 两个分量都取自并发容器/原子变量，读它不碰 H2 连接、不进 {@code synchronized(this)}。
     * </p>
     */
    public int getBacklogDepth() {
        final BlockingQueue<TraceSegment> q = writeQueue;
        if (q == null) {
            return 0;
        }
        return q.size() + inFlight.get();
    }

    /**
     * trace 表当前存活行数（增量口径：成功插入数 − 被水位清理数），{@code O(1)}。
     * <p>
     * <b>为什么不用 {@code COUNT(*)}</b>：十万行量级下那是一次全表扫，而读口按 10s 轮询；
     * 插入数与删除数都在写路径上顺手累加，读口只是相减。
     * </p>
     * <p>
     * 两个分量都是 {@code volatile} 读，读口不会阻塞写线程；代价是水位清理刚发生时
     * 可能瞬时偏大，量级不超过一个节流窗口（1024 行），下轮刷新即收敛。
     * 需要精确/权威行数仍走 {@link #size()}（{@code COUNT(DISTINCT trace_id)}）。
     * </p>
     */
    public long getLiveRowCount() {
        final long live = insertCount - deletedByRowCap;
        return live < 0L ? 0L : live;
    }

    /**
     * 自身状态快照（供 {@code /inner/sw/self-stat} 读口）：H2 段 + 环形文件段 + 积压段。
     *
     * <p>
     * 三段都只读内存计数与文件头游标，<b>不执行任何 SQL</b>、不进 {@code synchronized(this)}——
     * 读口是给人看"监控自己健康吗"的，绝不能自己变成一个把写线程堵住的耗时点。
     * </p>
     *
     * <p>
     * {@code estimatedBytes} 是<b>估算</b>（行数 × 单行实测成本），不是 H2 自报的字节数：
     * H2 2.x 没有内存读口（{@code INFORMATION_SCHEMA.SESSIONS} 无 {@code MEMORY_USED}、
     * {@code Session} 无 {@code getMemoryUsage}，已实测 2.1.212 / 2.3.232），
     * 真实字节数只能靠 {@code jmap -histo:live} 在带外测。口径与实测来源见
     * {@code docs/notes/2026-09-27-h2-mem-capacity-estimate.md} §7.1/§7.3。
     * </p>
     */
    public Map<String, Object> selfStatSnapshot() {
        final Map<String, Object> h2 = new LinkedHashMap<String, Object>(16);
        final long rows = getLiveRowCount();
        h2.put("enabled", Boolean.valueOf(enabled && connection != null));
        h2.put("rows", Long.valueOf(rows));
        h2.put("maxRows", Integer.valueOf(shadowMaxRows));
        h2.put("rowUsageRatio", Double.valueOf(shadowMaxRows <= 0 ? 0d
                : (double) rows / shadowMaxRows));
        h2.put("bytesPerRowEstimate", Long.valueOf(H2_BYTES_PER_ROW_ESTIMATE));
        h2.put("estimatedBytes", Long.valueOf(rows * H2_BYTES_PER_ROW_ESTIMATE));
        h2.put("insertedRows", Long.valueOf(insertCount));
        h2.put("evictedRows", Long.valueOf(deletedByRowCap));
        h2.put("oldestStartTimeMs", Long.valueOf(orMinusOne(oldestLiveStartTime)));
        h2.put("newestStartTimeMs", Long.valueOf(newestStartTime <= 0L ? -1L : newestStartTime));
        h2.put("coveredSpanMs", Long.valueOf(coveredSpanMs()));
        h2.put("errorCount", Long.valueOf(errorCount.get()));
        h2.put("auditWaterLevel", Integer.valueOf(AUDIT_WATER_LEVEL));

        final Map<String, Object> backlog = new LinkedHashMap<String, Object>(6);
        backlog.put("depth", Integer.valueOf(getBacklogDepth()));
        backlog.put("capacity", Integer.valueOf(WRITE_QUEUE_CAPACITY));
        backlog.put("dropped", Long.valueOf(getWriteQueueDropped()));

        final Map<String, Object> result = new LinkedHashMap<String, Object>(4);
        result.put("h2", h2);
        result.put("backlog", backlog);
        result.put("capped", cappedSnapshot());
        return result;
    }

    /**
     * 持久层实际覆盖的时间跨度（最新段 − 最老段，毫秒）；任一端缺失返回 {@code -1}。
     * <p>
     * 配 {@code maxRows} 与到达速率可判断"行数水位清理跟不跟得上"：跨度远小于
     * "进程运行时长 × 速率"能装下的量，说明老段正在被水位清掉。
     * </p>
     */
    private long coveredSpanMs() {
        if (oldestLiveStartTime == Long.MAX_VALUE || newestStartTime <= 0L) {
            return -1L;
        }        final long span = newestStartTime - oldestLiveStartTime;
        return span < 0L ? -1L : span;
    }

    private static long orMinusOne(final long value) {
        return value == Long.MAX_VALUE ? -1L : value;
    }

    /**
     * 环形载荷文件段快照：写指针位置、物理占用、覆盖轮次 + {@link CappedFileStorageStats} 全部计数。
     * <p>未启用或初始化失败时给 {@code enabled=false} 的空壳，读口据此显示"未启用"而非报错。</p>
     */
    private Map<String, Object> cappedSnapshot() {
        final Map<String, Object> m = new LinkedHashMap<String, Object>(24);
        m.put("enabled", Boolean.valueOf(cappedStorage != null));
        m.put("oldestPayloadId", Long.valueOf(oldestLivePayloadId == Long.MAX_VALUE ? -1L
                : oldestLivePayloadId));
        if (cappedStorage == null) {
            m.put("file", "");
            m.put("currIndex", Long.valueOf(0L));
            m.put("sizeBytes", Long.valueOf(0L));
            m.put("dataLenBytes", Long.valueOf(0L));
            m.put("usedRatio", Double.valueOf(0d));
            m.put("wrapCount", Long.valueOf(0L));
            m.put("oldestLiveIndex", Long.valueOf(0L));
            m.put("oldestPayloadReadable", Boolean.TRUE);
            m.put("stats", new LinkedHashMap<String, Object>());
            return m;
        }
        final long currIndex = cappedStorage.getCurrIndex();
        final long dataLen = cappedStorage.getDataLenBytes();
        m.put("file", cappedStorage.getFilePath());
        m.put("currIndex", Long.valueOf(currIndex));
        m.put("sizeBytes", Long.valueOf(cappedStorage.getSizeBytes()));
        m.put("dataLenBytes", Long.valueOf(dataLen));
        // 占用率按"已写字节 vs 一圈"算，绕圈后封顶为 100%——文件大小恒定，故不存在 >100%
        m.put("usedRatio", Double.valueOf(dataLen <= 0L ? 0d
                : Math.min(1d, currIndex / (double) dataLen)));
        m.put("wrapCount", Long.valueOf(cappedStorage.getWrapCount()));
        m.put("oldestLiveIndex", Long.valueOf(cappedStorage.getOldestLiveIndex()));
        // 交叉验证的核心一行：H2 里最早的载荷指针落在环的可读窗口左侧，就说明有行指向
        // 已被覆盖的载荷 —— 那是"过期"（环写满的预期结果），不是"丢失"。
        // 有了它，"是没采到 / 被水位清了 / 只是载荷过期"三种可能就能分开。
        m.put("oldestPayloadReadable", Boolean.valueOf(
                oldestLivePayloadId == Long.MAX_VALUE || oldestLivePayloadId == -1L
                        || oldestLivePayloadId >= cappedStorage.getOldestLiveIndex()));
        m.put("stats", cappedStorage.stats().snapshot());
        return m;
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
                final SegmentMetrics metrics = computeSegmentMetrics(log);
                final long payloadId = writePayload(log);
                insertSegmentRow(log, metrics, payloadId);
                trackInsertedRow(metrics.startTime, payloadId);
                enforceRowCap();
            } catch (Exception e) {
                recordError("storeLog", e);
            }
        }
    }

    /**
     * 记一行插入对增量水位的影响（一次比较，纯 CPU、不加锁、不分配）。
     * <p>只更新"最新段时间"—— 它永不变为无效（删除只删最老行）。"最老行"的水位不在这里算，
     * 见 {@link #refreshOldestLiveRow()}。</p>
     */
    private void trackInsertedRow(final long startTime, final long payloadId) {
        if (startTime > newestStartTime) {
            newestStartTime = startTime;
        }
    }

    private SegmentMetrics computeSegmentMetrics(final Log log) {
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
        return new SegmentMetrics(endpoint, startTime, endTime, (int) (endTime - startTime),
                isError, startTime / 60_000L);
    }

    /** 载荷先写环形文件（GZIP），再把指针写入 H2；写失败返回负值，由调用方置空 payload_id。 */
    private long writePayload(final Log log) {
        if (cappedStorage == null) {
            return -1L;
        }
        try {
            return cappedStorage.writeMessage(GSON.toJson(log.toMap()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            recordError("cappedWrite", e);
            return -1L;
        }
    }

    private void insertSegmentRow(final Log log, final SegmentMetrics metrics, final long payloadId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(H2SqlStatements.INSERT_SQL)) {
            ps.setString(1, log.getTraceId());
            ps.setString(2, log.getTraceSegmentId());
            ps.setString(3, metrics.endpoint);
            ps.setLong(4, metrics.startTime);
            ps.setLong(5, metrics.endTime);
            ps.setInt(6, metrics.latency);
            ps.setBoolean(7, metrics.isError);
            if (payloadId >= 0) {
                ps.setLong(8, payloadId);
            } else {
                ps.setNull(8, Types.BIGINT);
            }
            ps.setLong(9, metrics.timeBucket);
            ps.executeUpdate();
        }
    }

    private static final class SegmentMetrics {
        private final String endpoint;
        private final long startTime;
        private final long endTime;
        private final int latency;
        private final boolean isError;
        private final long timeBucket;

        SegmentMetrics(final String endpoint, final long startTime, final long endTime,
                final int latency, final boolean isError, final long timeBucket) {
            this.endpoint = endpoint;
            this.startTime = startTime;
            this.endTime = endTime;
            this.latency = latency;
            this.isError = isError;
            this.timeBucket = timeBucket;
        }
    }

    @Override
    public Map<String, Map<String, Object>> snapshot() {
        if (!enabled || connection == null) {
            return new LinkedHashMap<String, Map<String, Object>>();
        }
        synchronized (this) {
            try {
                return assembleSnapshots(readGroupedLogs());
            } catch (SQLException e) {
                recordError("snapshot", e);
                return new LinkedHashMap<String, Map<String, Object>>();
            }
        }
    }

    private Map<String, List<Map<String, Object>>> readGroupedLogs() throws SQLException {
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
                final Map<String, Object> logMap = readLogPayload(payloadId, payloadNull);
                if (logMap != null) {
                    addToGroup(grouped, traceId, logMap);
                }
            }
        }
        return grouped;
    }

    private void addToGroup(final Map<String, List<Map<String, Object>>> grouped, final String traceId,
            final Map<String, Object> logMap) {
        List<Map<String, Object>> logs = grouped.get(traceId);
        if (logs == null) {
            logs = new ArrayList<Map<String, Object>>();
            grouped.put(traceId, logs);
        }
        logs.add(logMap);
    }

    private Map<String, Map<String, Object>> assembleSnapshots(
            final Map<String, List<Map<String, Object>>> grouped) {
        final Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            final Map<String, Object> traceData = new LinkedHashMap<String, Object>();
            traceData.put("logs", entry.getValue());
            result.put(entry.getKey(), traceData);
        }
        return result;
    }

    private Map<String, Object> readLogPayload(final long payloadId, final boolean payloadNull) {
        if (payloadNull || payloadId < 0 || cappedStorage == null) {
            return null;
        }
        try {
            final byte[] bytes = cappedStorage.readMessage(payloadId);
            if (bytes == null) {
                return null;
            }
            return GSON.fromJson(new String(bytes, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Object>>(){}.getType());
        } catch (IOException e) {
            recordError("readLogPayload", e);
            return null;
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
                        final Map<String, Object> logMap = readLogPayload(payloadId, false);
                        if (logMap == null) {
                            anyExpired = true;
                        } else {
                            logs.add(logMap);
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
                        out.add(toRecentTraceRow(rs));
                    }
                }
            } catch (SQLException e) {
                recordError("recentTraces", e);
            }
        }
        return out;
    }

    private Map<String, Object> toRecentTraceRow(final ResultSet rs) throws SQLException {
        final Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("traceId", rs.getString("trace_id"));
        row.put("traceSegmentId", rs.getString("segment_id"));
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
        return row;
    }

    /**
     * 按 endpoint + 最小耗时阈值查询慢段。
     * <p>
     * 返回 <b>JDK 原生键值对行集合</b>（{@code List<Map<String,Object>>}，与 {@link #recentTraces} 同构）：
     * 每行键 {@code traceId / traceSegmentId / service / endpoint / startTime / latency / isError / hasPayload / payloadExpired}
     * （{@code startTime}、{@code latency} 为数值，可直接喂图表）。按 {@code latency} 降序、受 {@code limit} 截断。
     * 异常就地捕获并返回已读到的部分，绝不外抛。
     * </p>
     */
    public List<Map<String, Object>> querySlowTraces(final String endpoint, final long minLatencyMs, final int limit) {
        final List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (connection == null || endpoint == null || endpoint.isEmpty() || limit <= 0) {
            return out;
        }
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(H2SqlStatements.SELECT_SLOW_SQL)) {
                ps.setString(1, endpoint);
                ps.setLong(2, minLatencyMs);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(toRecentTraceRow(rs));
                    }
                }
            } catch (SQLException e) {
                recordError("querySlowTraces", e);
            }
        }
        return out;
    }

    /**
     * 行数水位上限：每 1024 行校验一次，超过 {@link #shadowMaxRows} 后按 {@code id} 水位清理。
     * <p>
     * 节流后表最多短暂超过水位 1024 行（软上限，随后收敛），
     * 换取省掉绝大多数行插入附带的 {@code MAX(id)} 查询与对象分配。
     * </p>
     */
    private void enforceRowCap() {
        // 写死 1024：逐行做水位校验会让每段 trace 都多一次 MAX(id) 查询（SQL 往返 + Statement/ResultSet 分配），
        // 按行数节流后这层开销趋近于 0；代价仅是最多短暂超水位 1024 行，对影子表是可接受的软上限。
        // TODO: 后续如仍需进一步省，可改为「计数 + drainLoop 空闲时搭便车」，或把该间隔抽成配置项。
        if (++insertCount % 1024 != 0) {
            return;
        }
        final int deleted = enforceCap(shadowMaxRows, H2SqlStatements.MAX_ID_SQL, H2SqlStatements.DELETE_CAP_SQL,
                "enforceRowCap");
        if (deleted > 0) {
            deletedByRowCap += deleted;
        }
        refreshOldestLiveRow();
    }

    /**
     * 刷新"最老存活行"水位：一次主键 seek（{@code ORDER BY id ASC LIMIT 1}）读一行，落地其
     * {@code start_time} 与 {@code payload_id}。
     *
     * <p>
     * <b>为什么是 seek 而不是 {@code MIN(start_time)}</b>：{@code start_time} 上没有索引，
     * {@code MIN()} 是全表扫（10 万行实测中位 23 ms、p90 44 ms），而本方法在
     * {@code synchronized(this)} 内、被写线程独占 —— 每 1024 行卡写线程几十毫秒不可接受。
     * seek 走主键、只读一行，实测 0.28 ms，摊到每段 0.0003 ms，与既有的 {@code MAX(id)}
     * 同一量级。
     * </p>
     *
     * <p>
     * <b>口径提示</b>：返回的是<b>按插入序最老</b>的那行，不是严格 {@code MIN(start_time)}。
     * 两者在乱序（异步段 / 时钟回拨）时会不同。刻意不合并"插入时增量 min"来补这一点 ——
     * 增量值不带删除补偿，会被行数水位清掉的行残留下来，取 min 反而永远选那个已删值。
     * </p>
     *
     * <p>出错时<b>保持旧值不动</b>：宁可让读口暂时显示旧水位，也不要写半截值或抛给写线程。</p>
     */
    private void refreshOldestLiveRow() {
        try (PreparedStatement ps = connection.prepareStatement(H2SqlStatements.SELECT_OLDEST_LIVE_SQL);
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                oldestLiveStartTime = rs.getLong(1);
                final long payloadId = rs.getLong(2);
                // payload_id 可能是 NULL（载荷写失败）：getLong 会把它读成 0，
                // 而 0 恰好是合法的首个逻辑偏移，必须靠 wasNull 区分。
                oldestLivePayloadId = rs.wasNull() ? -1L : payloadId;
            } else {
                // 表空了（clear 或水位清到 0）：回到"尚无行"
                oldestLiveStartTime = Long.MAX_VALUE;
                oldestLivePayloadId = -1L;
            }
        } catch (SQLException e) {
            recordError("refreshOldestLiveRow", e);
        }
    }

    /**
     * 按水位删除多余行，返回<b>实际删除行数</b>（未触发或出错返回 {@code -1}）。
     * <p>
     * 返回值不是给 SQL 用，是给读口用：{@code trace_segment} 到十万行量级时
     * {@code COUNT(*)} 是一次全表扫，而读口每 10s 轮询一次——用"插入数 − 删除数"
     * 增量维护存活行数，O(1) 且不需要扫表。
     * </p>
     */
    private int enforceCap(final int waterLevel, final String maxIdSql, final String deleteSql, final String op) {
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(maxIdSql)) {
            if (rs.next()) {
                final long maxId = rs.getLong(1);
                if (maxId > waterLevel) {
                    final long threshold = maxId - waterLevel;
                    try (PreparedStatement ps = connection.prepareStatement(deleteSql)) {
                        ps.setLong(1, threshold);
                        final int deleted = ps.executeUpdate();
                        return deleted;
                    }
                }
            }
            return 0;
        } catch (SQLException e) {
            recordError(op, e);
            return -1;
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

    // ==================== Phase 5：Trace 指标落库与查询 ====================

    /** 分辨率判定：{@code hour} 走小时表，其余走分钟表。 */
    private static boolean isHourResolution(final String resolution) {
        return "hour".equalsIgnoreCase(resolution);
    }

    /**
     * 批量幂等落指标行（指定分辨率）。同键重复写走 H2 原生 {@code MERGE} 的 UPDATE 分支，
     * 不重复计数；内部异常就地捕获。
     *
     * @return 实际写入行数（异常时为 0）
     */
    public int storeMetricRows(final String resolution, final List<MetricsRow> rows) {
        if (!enabled || connection == null || rows == null || rows.isEmpty()) {
            return 0;
        }
        final boolean hour = isHourResolution(resolution);
        final String sql = hour ? H2SqlStatements.MERGE_METRICS_HOUR_SQL : H2SqlStatements.MERGE_METRICS_MINUTE_SQL;
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (MetricsRow r : rows) {
                    ps.setString(1, r.getEndpoint());
                    ps.setLong(2, r.getTimeBucket());
                    ps.setLong(3, r.getRequestCount());
                    ps.setLong(4, r.getErrorCount());
                    ps.setLong(5, r.getSlowCount());
                    ps.setLong(6, r.getTotalLatency());
                    ps.setLong(7, r.getMaxLatency());
                    setNullableInt(ps, 8, r.getP50());
                    setNullableInt(ps, 9, r.getP90());
                    setNullableInt(ps, 10, r.getP95());
                    setNullableInt(ps, 11, r.getP99());
                    ps.setInt(12, r.getSampleCount());
                    ps.addBatch();
                }
                ps.executeBatch();
                return rows.size();
            } catch (SQLException e) {
                recordError("storeMetricRows", e);
                return 0;
            }
        }
    }

    private static void setNullableInt(final PreparedStatement ps, final int index, final int value)
            throws SQLException {
        if (value < 0) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    /**
     * 条件查询指标行（指定分辨率）。
     *
     * @param resolution {@code minute} / {@code hour}
     * @param endpoint   null 或空表示不限（返回范围内全部 endpoint，含保留键 {@code "*"}）
     * @param fromBucket 起始桶（含，按分辨率单位）
     * @param toBucket   结束桶（含，按分辨率单位）
     * @param limit      行数上限
     */
    public List<MetricsRow> queryMetricRows(final String resolution, final String endpoint,
            final long fromBucket, final long toBucket, final int limit) {
        final List<MetricsRow> out = new ArrayList<MetricsRow>();
        if (!enabled || connection == null || limit <= 0 || toBucket < fromBucket) {
            return out;
        }
        final boolean hour = isHourResolution(resolution);
        final boolean byEndpoint = endpoint != null && !endpoint.isEmpty();
        final String sql = hour
                ? (byEndpoint ? H2SqlStatements.SELECT_METRICS_HOUR_BY_ENDPOINT_SQL : H2SqlStatements.SELECT_METRICS_HOUR_RANGE_SQL)
                : (byEndpoint ? H2SqlStatements.SELECT_METRICS_MINUTE_BY_ENDPOINT_SQL : H2SqlStatements.SELECT_METRICS_MINUTE_RANGE_SQL);
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int i = 1;
                if (byEndpoint) {
                    ps.setString(i++, endpoint);
                }
                ps.setLong(i++, fromBucket);
                ps.setLong(i++, toBucket);
                ps.setInt(i, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(toMetricsRow(rs));
                    }
                }
            } catch (SQLException e) {
                recordError("queryMetricRows", e);
            }
        }
        return out;
    }

    /**
     * 按 endpoint 聚合查询（指定分辨率）：每端点一行，SQL 侧 {@code GROUP BY} 完成，
     * 不受"逐桶行 + 全局 LIMIT"的端点饥饿影响。按请求数降序。
     *
     * @param resolution {@code minute} / {@code hour}
     * @param fromBucket 起始桶（含，按分辨率单位）
     * @param toBucket   结束桶（含，按分辨率单位）
     * @param limit      endpoint 行数上限
     */
    public List<MetricsRow> aggregateMetricRows(final String resolution, final long fromBucket, final long toBucket,
            final int limit) {
        final List<MetricsRow> out = new ArrayList<MetricsRow>();
        if (!enabled || connection == null || limit <= 0 || toBucket < fromBucket) {
            return out;
        }
        final String sql = isHourResolution(resolution)
                ? H2SqlStatements.SELECT_METRICS_HOUR_AGG_SQL : H2SqlStatements.SELECT_METRICS_MINUTE_AGG_SQL;
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, fromBucket);
                ps.setLong(2, toBucket);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(toAggMetricsRow(rs));
                    }
                }
            } catch (SQLException e) {
                recordError("aggregateMetricRows", e);
            }
        }
        return out;
    }

    /**
     * 范围内每个 endpoint 的"有数据桶数"（{@code request_count > 0}），供**活跃平均 QPS** 分母。
     * 返回 JDK 原生 {@code Map<endpoint, 桶数>}（含全局保留键 {@code "*"}）；异常就地捕获并返回已读到的部分。
     */
    public Map<String, Integer> activeBucketCounts(final String resolution, final long fromBucket, final long toBucket) {
        final Map<String, Integer> out = new HashMap<String, Integer>();
        if (!enabled || connection == null || toBucket < fromBucket) {
            return out;
        }
        final String sql = isHourResolution(resolution)
                ? H2SqlStatements.SELECT_ACTIVE_BUCKETS_HOUR_SQL : H2SqlStatements.SELECT_ACTIVE_BUCKETS_MINUTE_SQL;
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, fromBucket);
                ps.setLong(2, toBucket);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString("endpoint"), rs.getInt("active"));
                    }
                }
            } catch (SQLException e) {
                recordError("activeBucketCounts", e);
            }
        }
        return out;
    }

    /**
     * 范围内 H2 已落库的桶集合（供聚合读口剔除内存中与已翻转账重叠的桶，避免重复计数）。
     * 返回 JDK 原生 {@code Set<Long>}；异常就地捕获并返回已读到的部分。
     */
    public Set<Long> distinctMetricBuckets(final String resolution, final long fromBucket, final long toBucket) {
        final Set<Long> out = new HashSet<Long>();
        if (!enabled || connection == null || toBucket < fromBucket) {
            return out;
        }
        final String sql = isHourResolution(resolution)
                ? H2SqlStatements.SELECT_METRICS_HOUR_BUCKETS_SQL : H2SqlStatements.SELECT_METRICS_MINUTE_BUCKETS_SQL;
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, fromBucket);
                ps.setLong(2, toBucket);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(Long.valueOf(rs.getLong(1)));
                    }
                }
            } catch (SQLException e) {
                recordError("distinctMetricBuckets", e);
            }
        }
        return out;
    }

    private MetricsRow toMetricsRow(final ResultSet rs) throws SQLException {
        return new MetricsRow(rs.getString("endpoint"), rs.getLong("time_bucket"),
                rs.getLong("request_count"), rs.getLong("error_count"), rs.getLong("slow_count"),
                rs.getLong("total_latency"), rs.getLong("max_latency"),
                nullableInt(rs, "p50"), nullableInt(rs, "p90"), nullableInt(rs, "p95"), nullableInt(rs, "p99"),
                rs.getInt("sample_count"));
    }

    /** 聚合行映射：在基础列之外，额外读"最差分钟"列（{@code worst_pXX}）与有数据桶数（{@code bucket_count}）。 */
    private MetricsRow toAggMetricsRow(final ResultSet rs) throws SQLException {
        return new MetricsRow(rs.getString("endpoint"), rs.getLong("time_bucket"),
                rs.getLong("request_count"), rs.getLong("error_count"), rs.getLong("slow_count"),
                rs.getLong("total_latency"), rs.getLong("max_latency"),
                nullableInt(rs, "p50"), nullableInt(rs, "p90"), nullableInt(rs, "p95"), nullableInt(rs, "p99"),
                rs.getInt("sample_count"),
                nullableInt(rs, "worst_p50"), nullableInt(rs, "worst_p90"),
                nullableInt(rs, "worst_p95"), nullableInt(rs, "worst_p99"),
                rs.getInt("bucket_count"));
    }

    private static int nullableInt(final ResultSet rs, final String column) throws SQLException {
        final int value = rs.getInt(column);
        return rs.wasNull() ? -1 : value;
    }

    /** 按水位删除过期指标行（指定分辨率）；截止桶由 Java 侧算好传入。 */
    public void deleteMetricsBefore(final String resolution, final long cutoffBucket) {
        if (!enabled || connection == null) {
            return;
        }
        final String sql = isHourResolution(resolution)
                ? H2SqlStatements.DELETE_METRICS_HOUR_BEFORE_SQL : H2SqlStatements.DELETE_METRICS_MINUTE_BEFORE_SQL;
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, cutoffBucket);
                ps.executeUpdate();
            } catch (SQLException e) {
                recordError("deleteMetricsBefore", e);
            }
        }
    }

    /**
     * 清空所有行（包级可见，供单元测试隔离使用）。
     * <p>连带复位行数计数——否则 {@link #getLiveRowCount()} 会一直停在清空前的水位。</p>
     */
    void clear() {
        if (connection == null) {
            return;
        }
        synchronized (this) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(H2SqlStatements.DELETE_ALL_SEGMENTS_SQL);
                stmt.execute(H2SqlStatements.DELETE_ALL_AUDITS_SQL);
                stmt.execute(H2SqlStatements.DELETE_ALL_METRICS_MINUTE_SQL);
                stmt.execute(H2SqlStatements.DELETE_ALL_METRICS_HOUR_SQL);
            } catch (SQLException e) {
                recordError("clear", e);
            }
            insertCount = 0L;
            deletedByRowCap = 0L;
            oldestLiveStartTime = Long.MAX_VALUE;
            oldestLivePayloadId = Long.MAX_VALUE;
            newestStartTime = 0L;
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
        enforceCap(AUDIT_WATER_LEVEL, H2SqlStatements.MAX_AUDIT_ID_SQL, H2SqlStatements.DELETE_AUDIT_CAP_SQL,
                "enforceAuditRowCap");
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
                        rows.add(toAuditRow(rs));
                    }
                }
            } catch (SQLException e) {
                recordError("recentAuditRows", e);
            }
        }
        return rows;
    }

    private Map<String, Object> toAuditRow(final ResultSet rs) throws SQLException {
        final Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("checkTime", rs.getString("check_time"));
        row.put("traceId", rs.getString("trace_id"));
        row.put("diffType", rs.getString("diff_type"));
        row.put("expected", rs.getString("expected"));
        row.put("actual", rs.getString("actual"));
        row.put("detail", rs.getString("detail"));
        return row;
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
        drainQueue();
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
