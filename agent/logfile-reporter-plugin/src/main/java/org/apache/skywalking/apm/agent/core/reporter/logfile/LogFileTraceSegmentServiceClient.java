package org.apache.skywalking.apm.agent.core.reporter.logfile;

import static org.apache.skywalking.apm.agent.core.conf.Config.Buffer.BUFFER_SIZE;
import static org.apache.skywalking.apm.agent.core.conf.Config.Buffer.CHANNEL_SIZE;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.context.TracingContext;
import org.apache.skywalking.apm.agent.core.context.TracingContextListener;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient;
import org.apache.skywalking.apm.agent.core.reporter.logfile.alert.AsyncTraceAlertDispatcher;
import org.apache.skywalking.apm.agent.core.reporter.logfile.alert.TraceAlertMetrics;
import org.apache.skywalking.apm.agent.core.reporter.logfile.metrics.MetricsRow;
import org.apache.skywalking.apm.agent.core.reporter.logfile.metrics.MetricsSink;
import org.apache.skywalking.apm.agent.core.reporter.logfile.metrics.TraceMetricsAggregator;
import org.apache.skywalking.apm.agent.core.reporter.logfile.metrics.TraceMetricsQuery;
import org.apache.skywalking.apm.agent.core.reporter.logfile.metrics.TraceMetricsRollup;
import org.apache.skywalking.apm.agent.core.reporter.logfile.storage.H2TraceSegmentStorage;
import org.apache.skywalking.apm.commons.datacarrier.DataCarrier;
import org.apache.skywalking.apm.commons.datacarrier.buffer.BufferStrategy;
import org.apache.skywalking.apm.commons.datacarrier.consumer.IConsumer;
import org.apache.skywalking.apm.dependencies.com.google.protobuf.TextFormat;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;

/**
 * <p>
 * TraceSegmentServiceClient 的本地实现：
 * 收集到的 TraceSegment 数据不再通过网络发送给 OAP，而是转成 Log/Map 后写入本地有界 FIFO 缓存，
 * 供状态暴露、日志上报等组件按需读取。
 * </p>
 * <p>
 * Refer to {@code DynamicEnabledTraceSegmentServiceClient}
 * </p>
 * <p> {@link https://github.com/apache/skywalking-java/blob/e0e8b3c8c304735991e057d431910ed1f4a57cdd/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/remote/TraceSegmentServiceClient.java}
 * 
 */
@OverrideImplementor(TraceSegmentServiceClient.class)
public class LogFileTraceSegmentServiceClient extends TraceSegmentServiceClient
		implements BootService, IConsumer<TraceSegment>, TracingContextListener {

	private static final ILog LOGGER = LogManager.getLogger(LogFileTraceSegmentServiceClient.class);

	// private Producer<byte[]> producer;

	private volatile DataCarrier<TraceSegment> carrier;

	private final AtomicBoolean enable;

	/** 本地 Trace 日志缓存条数上限，由 {@link LogFileReporterPluginConfig.Plugin.LogFileReporter} 配置，默认 1000 */
	private int maxLogSize;
	/** 按 traceId 合并的有界键式存储，FIFO 淘汰，单锁守护；在 prepare() 中初始化 */
	private KeyedLocalStore<String, Map<String, Object>> traceStore;

	private AsyncTraceAlertDispatcher traceAlertDispatcher;

	/** H2 影子存储（Phase 1：内存模式，与旧 KeyedLocalStore 路径并行双跑；h2.enabled=false 时为 null，零开销） */
	private H2TraceSegmentStorage traceSegmentStorage;

	/** Trace 指标聚合器（Phase 5：入口段 a1；metrics.enabled=false 时为 null，零开销） */
	private TraceMetricsAggregator metricsAggregator;
	/** 指标翻转定时线程（30s 周期：分钟翻转 → 分钟落库 → 上小时 rollup → 保留期清理） */
	private ScheduledExecutorService metricsFlushExecutor;
	private boolean metricsEnabled;
	private static final long METRICS_FLIP_INTERVAL_MS = 30_000L;
	/** 聚合器内存保留窗口（分钟桶），与 {@link TraceMetricsAggregator} 保持一致 */
	private static final int METRICS_LATE_WINDOW_BUCKETS = 3;

	/** debug 一致性比对开关（h2.compare_debug），仅 true 时触发对账 */
	private boolean compareDebug;
	/** 对账累积 traceIds（限频窗口内累积，到点一次比对） */
	private final Set<String> parityAccumulatedTraceIds = new HashSet<String>();
	/** 上次对账时间戳（ms），限频 ≥5s 一轮 */
	private volatile long parityLastCheckTime = 0;
	/** 累计已检查 traceId 数 */
	private long parityCheckedCount = 0;
	/** 累计差异总数 */
	private long parityTotalDiffs = 0;
	/** 对账日志限速（同类差异 30s 最多一条） */
	private volatile long parityLastErrorLogTime = 0;
	private static final long PARITY_INTERVAL_MS = 5_000L;
	private static final long PARITY_LOG_INTERVAL_MS = 30_000L;

	public LogFileTraceSegmentServiceClient() {
		this.enable = new AtomicBoolean(true);
	}

	// ==================================== @

	public void toggleEnable(boolean e) {
		if (LOGGER.isDebugEnable()) {
			LOGGER.debug("### Updating using new static config: {}", e);
		}

		enable.getAndSet(e);
		LOGGER.warn("### [ {} ] current logfile-reporter-enable status is [ {} ]", Config.Agent.SERVICE_NAME,
				isEnableLogfileReporter());
	}

	public boolean isEnableLogfileReporter() {
		return enable.get();
	}

	/**
	 * 返回当前缓存的快照，调用方不应修改。避免与消费线程并发修改导致 CME 或读到半写状态。
	 */
	public Map<String, Map<String, Object>> getLogfileStatMap() {
		if (traceStore == null) {
			return new HashMap<>();
		}
		return traceStore.snapshot();
	}

	/**
	 * 慢/错链路告警运行指标与配置快照。
	 * <p>
	 * 须由 Agent ClassLoader 侧调用（与 {@link AsyncTraceAlertDispatcher} 同一副本）；
	 * 插件拦截器请通过反射调用本方法，勿直接引用 {@link TraceAlertMetrics}。
	 * </p>
	 */
	public Map<String, Object> getTraceAlertMetrics() {
		return TraceAlertMetrics.get().snapshot();
	}

	/**
	 * H2 影子对账状态快照（供 {@code SWTraceParityUtils.statisticParity()} 经拦截器反射调用）。
	 * <p>
	 * 返回 JDK 原生 Map，不暴露 Agent 自定义类型。
	 * </p>
	 */
	public Map<String, Object> getParityStatus() {
		final Map<String, Object> result = new HashMap<String, Object>();
		result.put("compareDebug", compareDebug);
		result.put("checkedCount", parityCheckedCount);
		result.put("totalDiffs", parityTotalDiffs);
        result.put("h2Enabled", traceSegmentStorage != null);
        result.put("h2ErrorCount", traceSegmentStorage != null ? traceSegmentStorage.getErrorCount() : 0L);
        result.put("h2Size", traceSegmentStorage != null ? traceSegmentStorage.size() : 0);
        result.put("writeQueueDropped", traceSegmentStorage != null ? traceSegmentStorage.getWriteQueueDropped() : 0L);
        // 审计表：最近差异明细（来源 trace_parity_audit）+ 水位状态，供使用侧界面直接展示
        if (traceSegmentStorage != null) {
            result.put("auditRowCount", traceSegmentStorage.auditRowCount());
            result.put("auditWaterLevel", traceSegmentStorage.auditWaterLevel());
            result.put("recentDiffs", traceSegmentStorage.recentAuditRows(20));
        } else {
            result.put("auditRowCount", 0);
            result.put("auditWaterLevel", 0);
            result.put("recentDiffs", Collections.emptyList());
        }
        return result;
	}

	/** 按 traceId 取回整条链路（供 {@code SWTraceParityUtils.queryTrace()} 经拦截器反射调用）。 */
	public Map<String, Object> getTraceView(final String traceId) {
		if (traceSegmentStorage == null) {
			return new HashMap<String, Object>();
		}
		return traceSegmentStorage.queryTrace(traceId);
	}

	/** 最近 N 条 segment header（供挑选 traceId）。 */
	public List<Map<String, Object>> getRecentTraces(final int limit) {
		if (traceSegmentStorage == null) {
			return Collections.emptyList();
		}
		return traceSegmentStorage.recentTraces(limit);
	}

	/**
	 * Trace 指标实时快照（供 {@code SWMetricsUtils.statisticMetrics()} 经拦截器反射调用）。
	 * <p>
	 * 返回 JDK 原生 Map，不暴露 Agent 自定义类型；H2 不可用时仍返回内存累计快照（降级无损）。
	 * </p>
	 */
	public Map<String, Object> getMetricsStatus() {
		final Map<String, Object> result = new LinkedHashMap<String, Object>();
		result.put("enabled", metricsEnabled);
		result.put("storageEnabled", traceSegmentStorage != null);
		result.put("flipIntervalMs", METRICS_FLIP_INTERVAL_MS);
		result.put("currentBucket", System.currentTimeMillis() / TraceMetricsQuery.MINUTE_MS);
		result.put("lateWindowBuckets", METRICS_LATE_WINDOW_BUCKETS);
		result.put("minuteRetentionBuckets", TraceMetricsQuery.MINUTE_RETENTION_BUCKETS);
		result.put("hourRetentionBuckets", TraceMetricsQuery.HOUR_RETENTION_BUCKETS);
		result.put("maxQueryPoints", TraceMetricsQuery.MAX_QUERY_POINTS);
		if (metricsAggregator != null) {
			final Map<String, Object> snapshot = metricsAggregator.snapshot();
			result.put("buckets", enrichBucketStart(snapshot.get("buckets")));
			result.put("counters", snapshot.get("counters"));
		} else {
			result.put("buckets", Collections.emptyList());
			result.put("counters", Collections.emptyMap());
		}
		return result;
	}

	/**
	 * 指标条件查询（供 {@code SWMetricsUtils.queryMetrics(condition)} 经拦截器反射调用）。
	 * <p>
	 * condition 支持：{@code endpoint}（含保留键 {@code "*"}，缺省不限）、{@code fromBucket} /
	 * {@code toBucket}（分钟桶，缺省最近 24h）、{@code resolution}（缺省按跨度自动选）、
	 * {@code limit}（默认 200、上限 1000）。返回 JDK 原生 Map。H2 不可用时返回空集。
	 * </p>
	 */
	public Map<String, Object> queryMetrics(final Map<String, Object> condition) {
		final Map<String, Object> cond = condition != null ? condition : Collections.<String, Object>emptyMap();
		final Map<String, Object> result = new LinkedHashMap<String, Object>();
		final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
		final long nowMinute = System.currentTimeMillis() / TraceMetricsQuery.MINUTE_MS;
		long to = cond.containsKey("toBucket") ? asLong(cond.get("toBucket"), nowMinute) : nowMinute;
		// 缺省窗口 = 最近 24h（分钟分辨率），与默认路由阈值一致
		long from = cond.containsKey("fromBucket")
				? asLong(cond.get("fromBucket"), to - (24L * 60L - 1L))
				: to - (24L * 60L - 1L);
		if (to < from) {
			final long tmp = from;
			from = to;
			to = tmp;
		}
		String resolution = asString(cond.get("resolution"));
		if (resolution == null || resolution.isEmpty()) {
			resolution = TraceMetricsQuery.routeResolution(from, to);
		}
		final int limit = TraceMetricsQuery.clampLimit(asInteger(cond.get("limit")));
		if (asBoolean(cond.get("aggregate"))) {
			return aggregateMetrics(resolution, from, to, limit);
		}
		boolean truncated = false;
		if (traceSegmentStorage != null) {
			final String endpoint = asString(cond.get("endpoint"));
			long queryFrom = from;
			long queryTo = to;
			if ("hour".equals(resolution)) {
				queryFrom = from / TraceMetricsQuery.MINUTES_PER_HOUR;
				queryTo = to / TraceMetricsQuery.MINUTES_PER_HOUR;
			}
			final List<MetricsRow> raw = new ArrayList<MetricsRow>(traceSegmentStorage.queryMetricRows(resolution,
					endpoint, queryFrom, queryTo, TraceMetricsQuery.MAX_QUERY_POINTS));
			mergeLiveMinuteRows(raw, resolution, endpoint, from, to);
			truncated = raw.size() >= TraceMetricsQuery.MAX_QUERY_POINTS;
			final List<MetricsRow> sampled = TraceMetricsRollup.downsample(raw, limit);
			for (MetricsRow row : sampled) {
				rows.add(toBucketMap(resolution, row));
			}
		}
		result.put("resolution", resolution);
		result.put("rows", rows);
		result.put("count", rows.size());
		result.put("truncated", truncated);
		return result;
	}

	/**
	 * 把内存窗口中的实时分钟桶并入查询结果（仅分钟分辨率）：整分翻转前读口也能反映当前窗口。
	 * 同 (endpoint, 桶) 已在 H2 结果中的不重复追加。
	 */
	private void mergeLiveMinuteRows(final List<MetricsRow> rows, final String resolution, final String endpoint,
			final long fromMinute, final long toMinute) {
		if (metricsAggregator == null || !"minute".equals(resolution)) {
			return;
		}
		final Set<String> seen = new HashSet<String>();
		for (MetricsRow r : rows) {
			seen.add(r.getEndpoint() + "@" + r.getTimeBucket());
		}
		for (MetricsRow r : metricsAggregator.memoryRows()) {
			if (r.getTimeBucket() < fromMinute || r.getTimeBucket() > toMinute) {
				continue;
			}
			if (endpoint != null && !endpoint.isEmpty() && !endpoint.equals(r.getEndpoint())) {
				continue;
			}
			if (seen.add(r.getEndpoint() + "@" + r.getTimeBucket())) {
				rows.add(r);
			}
		}
	}

	/**
	 * 按 endpoint 聚合查询（`aggregate=true`）：SQL 侧 `GROUP BY endpoint` 出每端点一行，
	 * 避免"全端点逐桶行 + 全局 LIMIT"被字典序端点饿死；再并入内存实时窗口（分钟分辨率）。
	 * 行按请求数降序；`truncated=true` 表示 endpoint 数达到上限。
	 */
	private Map<String, Object> aggregateMetrics(final String resolution, final long fromMinute, final long toMinute,
			final int limit) {
		final Map<String, Object> result = new LinkedHashMap<String, Object>();
		final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
		long queryFrom = fromMinute;
		long queryTo = toMinute;
		if ("hour".equals(resolution)) {
			queryFrom = fromMinute / TraceMetricsQuery.MINUTES_PER_HOUR;
			queryTo = toMinute / TraceMetricsQuery.MINUTES_PER_HOUR;
		}
		final List<MetricsRow> agg = traceSegmentStorage != null
				? new ArrayList<MetricsRow>(traceSegmentStorage.aggregateMetricRows(resolution, queryFrom, queryTo, limit))
				: new ArrayList<MetricsRow>();
		final boolean truncated = agg.size() >= limit;
		// 并入内存实时窗口（分钟分辨率），让当前未翻转的桶也计入表格
		if (metricsAggregator != null && "minute".equals(resolution)) {
			for (MetricsRow r : metricsAggregator.memoryRows()) {
				if (r.getTimeBucket() >= fromMinute && r.getTimeBucket() <= toMinute) {
					agg.add(r);
				}
			}
		}
		final List<MetricsRow> combined = TraceMetricsRollup.mergeByEndpoint(agg, 0L);
		Collections.sort(combined, new Comparator<MetricsRow>() {
			@Override
			public int compare(final MetricsRow a, final MetricsRow b) {
				return Long.compare(b.getRequestCount(), a.getRequestCount());
			}
		});
		final int cap = Math.min(combined.size(), limit);
		for (int i = 0; i < cap; i++) {
			rows.add(combined.get(i).toMap());
		}
		result.put("resolution", resolution);
		result.put("aggregate", Boolean.TRUE);
		result.put("rows", rows);
		result.put("count", rows.size());
		result.put("truncated", truncated);
		return result;
	}

	private static boolean asBoolean(final Object value) {
		if (value instanceof Boolean) {
			return ((Boolean) value).booleanValue();
		}
		if (value instanceof String) {
			return Boolean.parseBoolean((String) value);
		}
		return false;
	}

	private static List<Map<String, Object>> enrichBucketStart(final Object bucketsObj) {
		final List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
		if (bucketsObj instanceof List) {
			for (Object item : (List<?>) bucketsObj) {
				if (item instanceof Map) {
					@SuppressWarnings("unchecked")
					final Map<String, Object> row = new LinkedHashMap<String, Object>((Map<String, Object>) item);
					row.put("bucketStart", bucketStartText("minute", asLong(row.get("timeBucket"), 0L)));
					out.add(row);
				}
			}
		}
		return out;
	}

	private static Map<String, Object> toBucketMap(final String resolution, final MetricsRow row) {
		final Map<String, Object> map = row.toMap();
		map.put("bucketStart", bucketStartText(resolution, row.getTimeBucket()));
		return map;
	}

	private static String bucketStartText(final String resolution, final long bucket) {
		final long ms = "hour".equals(resolution) ? bucket * TraceMetricsQuery.HOUR_MS
				: bucket * TraceMetricsQuery.MINUTE_MS;
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms));
	}

	private static String asString(final Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private static long asLong(final Object value, final long defaultValue) {
		if (value instanceof Number) {
			return ((Number) value).longValue();
		}
		if (value instanceof String) {
			try {
				return Long.parseLong((String) value);
			} catch (NumberFormatException ignored) {
				return defaultValue;
			}
		}
		return defaultValue;
	}

	private static Integer asInteger(final Object value) {
		if (value instanceof Number) {
			return Integer.valueOf(((Number) value).intValue());
		}
		if (value instanceof String) {
			try {
				return Integer.valueOf(Integer.parseInt((String) value));
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	// ==================================== @Override

	@Override
	public void prepare() {
		Integer configured = LogFileReporterPluginConfig.Plugin.LogFileReporter.MAX_LOG_SIZE;
		this.maxLogSize = (configured != null && configured > 0) ? configured : 1000;
		LOGGER.info("### LogFileTraceSegmentServiceClient.prepare方法被调用，配置的maxLogSize为: {}，实际使用为: {}", configured, this.maxLogSize);

		final int maxSize = this.maxLogSize;
		traceStore = new KeyedLocalStore<String, Map<String, Object>>(maxSize);

		LOGGER.warn("### prepare - LogFileTraceSegmentServiceClient - maxLogSize is [ {} ]", maxLogSize);

		traceAlertDispatcher = AsyncTraceAlertDispatcher.createIfEnabled();
		if (traceAlertDispatcher != null) {
			LOGGER.info("### [TraceAlert] LogFileTraceSegmentServiceClient bound to AsyncTraceAlertDispatcher.");
		} else {
			LOGGER.info("### [TraceAlert] LogFileTraceSegmentServiceClient: trace alert dispatcher is off.");
		}

		// 本agent脱离OAP, 所以不需要监听GRPC
		//super.prepare();

		// H2 影子存储初始化（Phase 1：内存模式，与旧路径并行双跑）
		h2Config();

		// Trace 指标聚合初始化（Phase 5：入口段 a1 + H2 内存模式多分辨率）
		metricsConfig();
	}

	private void metricsConfig() {
		final Boolean configured = LogFileReporterPluginConfig.Plugin.LogFileReporter.Metrics.ENABLED;
		this.metricsEnabled = configured == null || configured;
		if (!metricsEnabled) {
			LOGGER.info("### [Metrics] trace metrics aggregation is disabled (metrics.enabled=false).");
			return;
		}
		final Integer slowConfig = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.DEFAULT_SLOW_THRESHOLD_MS;
		final long slowThresholdMs = (slowConfig != null && slowConfig > 0) ? slowConfig.longValue() : 3000L;
		final MetricsSink sink = new MetricsSink() {
			@Override
			public void store(final List<MetricsRow> rows) {
				if (traceSegmentStorage != null) {
					traceSegmentStorage.storeMetricRows("minute", rows);
				}
			}
		};
		this.metricsAggregator = new TraceMetricsAggregator(sink, Config.Agent.SERVICE_NAME, slowThresholdMs);
		startMetricsFlushTimer();
		LOGGER.info("### [Metrics] TraceMetricsAggregator initialized (service={}, slowThresholdMs={}, storageEnabled={}).",
				Config.Agent.SERVICE_NAME, slowThresholdMs, traceSegmentStorage != null);
	}

	private void startMetricsFlushTimer() {
		final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
			@Override
			public Thread newThread(final Runnable r) {
				final Thread t = new Thread(r, "TraceMetrics-Flush");
				t.setDaemon(true);
				return t;
			}
		});
		executor.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				flushMetricsSafely();
			}
		}, METRICS_FLIP_INTERVAL_MS, METRICS_FLIP_INTERVAL_MS, TimeUnit.MILLISECONDS);
		this.metricsFlushExecutor = executor;
	}

	/** 30s 翻转周期：分钟翻转落库 → 上一小时 rollup → 保留期清理；异常全部吞掉。 */
	private void flushMetricsSafely() {
		if (metricsAggregator == null) {
			return;
		}
		try {
			final long now = System.currentTimeMillis();
			metricsAggregator.flushClosedBuckets(now);
			rollupPreviousHour(now);
			cleanupMetricsRetention(now);
		} catch (Exception e) {
			LOGGER.error(e, "### [Metrics] flush cycle failed (swallowed).");
		}
	}

	/**
	 * 把上一小时的分钟行 rollup 成小时行（幂等覆盖）。
	 * <p>
	 * 每次翻转都重跑上一小时：迟到段在保留窗口内改写分钟行后，本小时行会被重算覆盖，
	 * 近似值随之收敛（分位为请求数加权平均，v1 已知偏差）。
	 * </p>
	 */
	private void rollupPreviousHour(final long now) {
		if (traceSegmentStorage == null) {
			return;
		}
		final long prevHourBucket = now / TraceMetricsQuery.HOUR_MS - 1L;
		final long fromMinute = prevHourBucket * TraceMetricsQuery.MINUTES_PER_HOUR;
		final long toMinute = fromMinute + TraceMetricsQuery.MINUTES_PER_HOUR - 1L;
		final List<MetricsRow> minuteRows = traceSegmentStorage.queryMetricRows("minute", null, fromMinute, toMinute,
				TraceMetricsQuery.MAX_QUERY_POINTS);
		if (minuteRows.isEmpty()) {
			return;
		}
		final List<MetricsRow> hourRows = TraceMetricsRollup.toHourRows(minuteRows, prevHourBucket);
		traceSegmentStorage.storeMetricRows("hour", hourRows);
	}

	private void cleanupMetricsRetention(final long now) {
		if (traceSegmentStorage == null) {
			return;
		}
		traceSegmentStorage.deleteMetricsBefore("minute",
				now / TraceMetricsQuery.MINUTE_MS - TraceMetricsQuery.MINUTE_RETENTION_BUCKETS);
		traceSegmentStorage.deleteMetricsBefore("hour",
				now / TraceMetricsQuery.HOUR_MS - TraceMetricsQuery.HOUR_RETENTION_BUCKETS);
	}
	
	private void h2Config() {
		if (LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.ENABLED) {
			final int shadowMaxRows = LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.SHADOW_MAX_ROWS != null
					? LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.SHADOW_MAX_ROWS : 2000;
			final boolean cappedEnabled = LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.PAYLOAD_CAPPED_ENABLED != null
					&& LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.PAYLOAD_CAPPED_ENABLED;
			final String cappedFile = LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.PAYLOAD_CAPPED_FILE != null
					? LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.PAYLOAD_CAPPED_FILE : "trace-payload.capped.db";
			final int cappedSizeMb = LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.PAYLOAD_CAPPED_SIZE_MB != null
					? LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.PAYLOAD_CAPPED_SIZE_MB : 128;
			final long cappedSizeBytes = (long) cappedSizeMb * 1024L * 1024L;
			traceSegmentStorage = new H2TraceSegmentStorage(true, shadowMaxRows, cappedEnabled, new File(cappedFile), cappedSizeBytes);
			LOGGER.info("### [H2Shadow] traceSegmentStorage initialized (shadowMaxRows={}, cappedEnabled={}, cappedFile={}, cappedSizeMb={}).",
					shadowMaxRows, cappedEnabled, cappedFile, cappedSizeMb);
			// H2 Web Console（可选，默认关闭；仅测试环境开启）
			if (LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.CONSOLE_ENABLED != null
					&& LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.CONSOLE_ENABLED) {
				final int consolePort = LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.CONSOLE_PORT != null
						? LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.CONSOLE_PORT : 8092;
				traceSegmentStorage.startConsole(consolePort);
			}
		} else {
			LOGGER.info("### [H2Shadow] traceSegmentStorage is disabled (h2.enabled=false).");
		}

		// debug 一致性比对开关
		this.compareDebug = LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.COMPARE_DEBUG != null
				&& LogFileReporterPluginConfig.Plugin.LogFileReporter.H2.COMPARE_DEBUG;
		if (compareDebug) {
			LOGGER.info("### [H2Shadow] compare_debug is ON — parity checks will run every {}ms.", PARITY_INTERVAL_MS);
		}		
	}

	@Override
	public void boot() {
		carrier = new DataCarrier<>(CHANNEL_SIZE, BUFFER_SIZE, BufferStrategy.IF_POSSIBLE);
		carrier.consume(this, 1);
	}

	@Override
	public void onComplete() {
		TracingContext.ListenerManager.add(this);
	}

	@Override
	public void shutdown() {
		TracingContext.ListenerManager.remove(this);
		carrier.shutdownConsumers();
		if (traceAlertDispatcher != null) {
			traceAlertDispatcher.shutdown();
		}
		// 指标：停定时器 → 最后一次翻转 + rollup（尽量少丢最后的窗口）→ 再关存储
		if (metricsFlushExecutor != null) {
			metricsFlushExecutor.shutdownNow();
		}
		if (metricsAggregator != null) {
			try {
				final long now = System.currentTimeMillis();
				metricsAggregator.flushClosedBuckets(now);
				rollupPreviousHour(now);
			} catch (Exception e) {
				LOGGER.error(e, "### [Metrics] final flush on shutdown failed (swallowed).");
			}
		}
		if (traceSegmentStorage != null) {
			traceSegmentStorage.close();
		}
	}

	@Override
	public void consume(final List<TraceSegment> data) {
		// 触发时机：由 DataCarrier（数据传输队列）批量消费时触发。DataCarrier 会把队列里的 TraceSegment 批量取出，调用
		// consume(List<TraceSegment> data)。
		// 你可以在这里做“批量 TraceSegment 的统一处理”，比如：批量序列化、写日志、落盘、上报等。
		

		// TODO 处理profile. 参考基类的consume方法

		if (!isEnableLogfileReporter()) {
			LOGGER.info(
					"###consume. disable the logfile-reporter [ {} ] which save data to log-file. the collection size of data is [ {} ]",
					Config.Agent.SERVICE_NAME, data.size());
			return;
		}

		if (LOGGER.isDebugEnable()) {
			LOGGER.debug(
					"### current logfile-reporter status [ {} ] is [ {} ], the colletion size of data is [ {} ], the colletion size of cache is [ {} ]",
					Config.Agent.SERVICE_NAME, isEnableLogfileReporter(), data.size(), traceStore.size());
		}
		// 《SW原理 - 基本概念 （ TraceSegment ）》
		// 1. 一个trace由多个tracesegment构成
		// 2. 一个Tracesegemnt记录了一个请求在一个线程中的执行流程
		// 3. 一个TraceSegment内包含一个Span集合
		// 4. TraceSegment 的核心字段结构如下：
		// 4.1 traceSegmentId（ID 类型）：通过GlobalIdGenerator 生成，是TraceSegment 的全局唯一标识。
		// 4.2 ref（TraceSegmentRef类型）：它指向父 TraceSegment。在 RPC 调用、HTTP 请求等跨进程调用中，一个
		// TraceSegment 最多只有一个父 TraceSegment，但是在一个 Consumer 批量消费 MQ 消息时，同一批内的消息可能来自不同的
		// Producer，这就会导致 Consumer 线程对应的 TraceSegment 有多个父 TraceSegment了，系统只保留第一个父
		// TraceSegment,早期版本是保留了全部 。
		// 4.3 relatedGlobalTraceId（DistributedTraceIds 类型）：记录当前 TraceSegment 所属 Trace 的
		// Trace ID，批处理场景下也只保留第一个。
		// 4.4 spans（List<AbstractTracingSpan> 类型）：当前 TraceSegment 包含的所有 Span。
		// 4.5 ignore（boolean 类型）：ignore 字段表示当前 TraceSegment是否被忽略。主要是为了忽略一些问题
		// TraceSegment（据说是对只包含一个 Span 的 Trace 进行采样收集）。
		// 4.6 isSizeLimited（boolean 类型）：每个 TraceSegment 中 Span 的个数是有上限的（默认值为
		// 300，可动态配置），超过上限之后，就不再添加 Span了；这是一个内存保护措施。
		// 将 SegmentObject 转换为自定义的 Log 对象，并存入缓存，供外部读取
		final List<SegmentObject> collect = data.stream().map(TraceSegment::transform).collect(Collectors.toList());
		// final String globalTraceid = collect.get(0).getTraceId();
		// final List<Log> logList = new ArrayList<>();
		for (SegmentObject segment : collect) {
			// 假设 Log 类有对应的 setter 方法，或者构造方法
			// 1. traceId：全局唯一，标识一次完整的分布式调用。
			// 2. traceSegmentId：局部唯一，标识某个服务/线程/进程中的一个调用片段。
			// 3. 一个 traceId 下可以有多个 traceSegmentId，它们通过“引用关系”串联成完整的调用链。
			Log log = SegmentLogConverter.toLog(segment);
			// logList.add(log);

			// 这里是traceId一样的放到一起
			// 先判断当前traceId是否已存在于logfileStatMap中，如果存在则合并logList，否则直接放入
			mergeLogIntoStatMap(log);

			// Phase 5 入口段 a1：逐段喂入指标聚合器（非入口段由聚合器内部排除并计数）。
			// 跑在 DataCarrier 消费线程上，只改内存桶、零 I/O，不为业务线程增加延迟。
			if (metricsAggregator != null) {
				metricsAggregator.onSegment(segment);
			}
		}
		// logfileStatMap.put(globalTraceid, new LogCollection(logList).toMap());

		// Phase 1 影子 accept：既有逻辑之后新增一次 H2 影子写入；旧路径零改动、零行为变化。
		// accept 内部异常全部捕获（计数 + 限速日志），绝不外抛——"监控只能是助力，不是阻碍"。
		if (traceSegmentStorage != null) {
			traceSegmentStorage.accept(data);
			// debug 一致性比对：批次末尾、仅 compare_debug=true、只比本批涉及的 traceIds
			if (compareDebug) {
				runParityCheck(collect);
			}
		}
	}

	/**
	 * debug 一致性比对触发器：限频 ≥5s，期间累积 traceIds，到点一次取两份快照比对。
	 * <p>
	 * 比对自身异常吞掉——绝不影响主流程。差异落日志（计数 + 限速明细）+ H2 审计表。
	 * </p>
	 */
	private void runParityCheck(final List<SegmentObject> batch) {
		// 收集本批 traceIds
		final Set<String> batchIds = new HashSet<String>();
		for (SegmentObject seg : batch) {
			batchIds.add(seg.getTraceId());
		}
		final Set<String> idsToCheck;
		synchronized (parityAccumulatedTraceIds) {
			parityAccumulatedTraceIds.addAll(batchIds);
			final long now = System.currentTimeMillis();
			if (now - parityLastCheckTime < PARITY_INTERVAL_MS) {
				return; // 未到限频窗口，只累积不比对
			}
			parityLastCheckTime = now;
			idsToCheck = new HashSet<String>(parityAccumulatedTraceIds);
			parityAccumulatedTraceIds.clear();
		}
		try {
			// 异步写：比对前等写队列排空，避免"刚 accept、尚未落库"的假差异
			traceSegmentStorage.awaitIdle(2000L);
			final Map<String, Map<String, Object>> oldSnapshot = getLogfileStatMap();
			final Map<String, Map<String, Object>> newSnapshot = traceSegmentStorage.snapshot();
			final TraceParityComparator.Report report = TraceParityComparator.compare(oldSnapshot, newSnapshot, idsToCheck);
			parityCheckedCount += report.getCheckedCount();
			if (report.hasDiffs()) {
				parityTotalDiffs += report.getTotalDiffs();
				final long now = System.currentTimeMillis();
				if (now - parityLastErrorLogTime > PARITY_LOG_INTERVAL_MS) {
					parityLastErrorLogTime = now;
					LOGGER.warn("### [H2Shadow] Parity check: checked={}, totalDiffs={}, diffCounts={}, samples={}",
							report.getCheckedCount(), report.getTotalDiffs(), report.getDiffCounts(), report.getSamples());
				}
				traceSegmentStorage.insertAuditRows(report.getSamples());
			}
		} catch (Exception e) {
			// 比对自身异常吞掉——绝不影响主流程
			LOGGER.error(e, "### [H2Shadow] Parity check failed (swallowed).");
		}
	}

	/**
	 * 按 traceId 合并：已有则把新段追加到 logs 列表，否则放入首个段的集合。
	 * <p>
	 * 合并由 {@link KeyedLocalStore#merge} 在存储锁内原子完成（返回更新后的值）。存储值采用
	 * "只替换不原地修改"的发布方式：remapper 总是返回新构造的合并值，已发布的值之后不会再被
	 * 改写，告警分发器可直接遍历合并结果，且 webhook 网络 I/O 不占用存储临界区。
	 * </p>
	 */
	private void mergeLogIntoStatMap(Log log) {
		final String globalTraceid = log.getTraceId();
		final Map<String, Object> incoming = new LogCollection(Collections.singletonList(log)).toMap();
		final Map<String, Object> merged = traceStore.merge(globalTraceid, incoming, (existing, batch) -> {
			final Map<String, Object> combined = new HashMap<String, Object>(existing);
			Object logsObj = combined.get("logs");
			if (logsObj instanceof List) {
				@SuppressWarnings("unchecked")
				final List<Map<String, Object>> logs = (List<Map<String, Object>>) logsObj;
				@SuppressWarnings("unchecked")
				final List<Map<String, Object>> incomingLogs = (List<Map<String, Object>>) batch.get("logs");
				if (incomingLogs != null) {
					final List<Map<String, Object>> appendedLogs = new ArrayList<Map<String, Object>>(logs);
					appendedLogs.addAll(incomingLogs);
					combined.put("logs", appendedLogs);
				}
			}
			return combined;
		});
		if (traceAlertDispatcher != null && merged != null) {
			traceAlertDispatcher.afterTraceMerged(globalTraceid, merged);
		}
	}

	@Override
	public void onError(final List<TraceSegment> data, final Throwable t) {
		LOGGER.error(t, "Consume trace segments to local cache failed, batch size: {}.", data.size());
	}

	@Override
	public void onExit() {
		carrier.shutdownConsumers();
	}

	@Override
	public void afterFinished(final TraceSegment traceSegment) {
		// afterFinished(final TraceSegment traceSegment) 方法会在每个 TraceSegment
		// 完成（即一次完整的链路追踪数据采集结束）时被 SkyWalking Agent 回调。
		// 原理是：SkyWalking 的核心链路追踪逻辑（如 TracingContext）在采集完一段 Trace 后，会遍历注册的
		// TracingContextListener，依次调用其 afterFinished 方法，把刚刚完成的 TraceSegment
		// 传递给监听者，实现自定义处理（如上报、落盘等）。
		// 你可以在这里做“单条 TraceSegment 完成后的自定义处理”，比如：把它放到队列、缓存、异步处理等。
		// 这样可以解耦采集与后续处理逻辑。
		if (LOGGER.isDebugEnable()) {
			LOGGER.debug("Trace segment reporting, traceId: {}", traceSegment.getTraceSegmentId());
		}

		if (traceSegment.isIgnore()) {
			LOGGER.debug("Trace[TraceId={}] is ignored.", traceSegment.getTraceSegmentId());
			return;
		}

		if (!isEnableLogfileReporter()) {
			LOGGER.info("### afterFinished. disable the logfile-reporter [ {} ] which save data to log-file.",
					Config.Agent.SERVICE_NAME);
			return;
		}
		carrier.produce(traceSegment);
//
//		// =====================================================================
//		final SegmentObject segment = traceSegment.transform();
//		Log log = new Log();
//		// 假设 Log 类有对应的 setter 方法，或者构造方法
//		log.setTraceId(segment.getTraceId());
//		log.setTraceSegmentId(segment.getTraceSegmentId());
//		log.setService(segment.getService());
//		log.setServiceInstance(segment.getServiceInstance());
//		log.setIsSizeLimited(segment.getIsSizeLimited());
//
//		List<Log.SpanInfo> spanInfoList = new ArrayList<>();
//		for (SpanObject span : segment.getSpansList()) {
//			Log.SpanInfo spanInfo = new Log.SpanInfo();
//			spanInfo.setSpanId(span.getSpanId());
//			spanInfo.setOperationName(span.getOperationName());
//			spanInfo.setStartTime(span.getStartTime());
//			spanInfo.setEndTime(span.getEndTime());
//			spanInfo.setSpanType(span.getSpanType().toString());
//			spanInfo.setSpanLayer(span.getSpanLayer().toString());
//			spanInfo.setComponentId(span.getComponentId());
//			spanInfo.setIsError(span.getIsError());
//			spanInfoList.add(spanInfo);
//		}
//		log.setSpans(spanInfoList);
//
//		final String globalTraceid = segment.getTraceId();
//		logfileStatMap.put(globalTraceid, log.toMap());
	}

	/**
	 * 本地实现无需从 Properties 初始化，保留空实现以满足接口约定。
	 * 在 debug 模式下输出传入的配置项，便于扩展时快速确认可用 key。
	 */
	@Override
	public void init(Properties prop) {
		if (prop != null && !prop.isEmpty()) {
			for (String name : prop.stringPropertyNames()) {
				LOGGER.info("### init property: {} = {}", name, prop.getProperty(name));
			}
		}
	}
}
