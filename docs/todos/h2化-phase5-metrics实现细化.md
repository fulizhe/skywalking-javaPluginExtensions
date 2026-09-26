# H2 化 Phase 5：trace-based Metrics 实现细化（决策锁定版）

> **实现状态（2026-09-25 核对代码后回写）**：**Phase 5 已实现完成**（spec 票 01–06 + 30d；`logfile-reporter-plugin` 单测 132 全绿，`validate.ps1` / `validate-h2.ps1` 全绿）。本文作为**决策历史**保留，逐条标注如下。
>
> **⚠️ 图例**：
> - ~~删除线~~ = **已实现并验证**（括号内为代码落点）
> - `❌` = **已被 spec 取代，永不实现**（不是"漏做"，是主动推翻；勿再照本文施工）
> - 无标注 = 真·未实现，是待评估的剩余部分
>
> **⚠️ 2026-09-24 路线调整（已落地）**：本文的 **§3.1 合并事件挂钩**、**§3.2 告警包改造**（evaluator 注入 / `evaluate(snapshot,bool)` / `entryStartTimeMs`）、**§3.3 部分口径**、**§3.4 traceId 去重**、**§7 TTL** 与 **§11 落地小步** 中相应部分**已被取代**——Phase 5 实际落地为 **入口段 a1（`consume` 逐段 `onSegment`）+ H2 内存模式 + 多分辨率（分钟 + 小时 rollup）**，**不复用 `TraceEvaluator` / 不改告警包**。**权威 spec：`.scratch/h2-metrics/spec.md`**，冲突处以 spec 为准。
>
> **文档定位**：把 [`h2化-phase5-metrics讨论.md`](h2化-phase5-metrics讨论.md) 的推演**落到当时代码**上、逐项锁定决策的历史文档。**已实现部分保留供追溯，被取代部分以 `❌` 标出；一切冲突以 spec 为准。**
>
> **单一场景前提**：定位为**单体应用**（单一 JVM、无 OAP 聚合、无多服务）。故 `service` 恒为 `Config.Agent.SERVICE_NAME`，**没有 instance 维度、没有服务地图**；真正有区分度的维度只有 **endpoint（＝operationName）**。
>
> **本文基于的代码状态（撰写当时的历史快照，行号已过期）**：
> - 采集路径：`LogFileTraceSegmentServiceClient.consume` → `mergeLogIntoStatMap`（`…/logfile/LogFileTraceSegmentServiceClient.java:376`），末行调 `traceAlertDispatcher.afterTraceMerged(traceId, merged)`（:395-397）。
> - 判定：`TraceEvaluator.evaluate`（`…/logfile/alert/TraceEvaluator.java:94`），返回 `EvaluationResult{alertTypes, entryOperation, url, durationMs, thresholdMs, errorSpanCount}`（:193-238）；`TraceSpanUtils.maxDurationMs`（`…/alert/TraceSpanUtils.java:71`）。
> - 存储：`storage/` 子包已就绪（`TraceSegmentStorage` / `H2TraceSegmentStorage` / `CappedFileStorage` / `H2SqlStatements`）；H2 `mem` 模式、**独立写线程 + 有界队列 4096、满则丢弃计数**；连接访问走 `synchronized(this)`。
> - 对外 Facade 范式：`LogfileReporterStatusExposeInterceptor`（`status`）、`TraceParityStatusExposeInterceptor` + `SWTraceParityUtils`（带参、可反射取值）。
> - 配置：`LogFileReporterPluginConfig.Plugin.LogFileReporter.{Alert, H2}`（`…/logfile/LogFileReporterPluginConfig.java`）。

---

## 1. 范围与不变式

**做**：❌ ~~在**合并视图事件**上流式聚合 trace 指标~~ → **实际**：在 `consume` 里对每条原始 segment 调 `metricsAggregator.onSegment(segment)`，按"入口段即一次事务"（a1）聚合 → 内存分钟桶 → 整分批量 upsert 进 `trace_metrics_minute` + 小时 rollup 进 `trace_metrics_hour`；新增 `SWMetricsUtils` Facade 暴露只读 `Map`。

**不做 / 不变**：
- ~~normal 明细不落库（Phase 1/ADR-03 既定），指标必须在采集流上算，**不能事后从 H2 明细聚合**。~~（仍成立，已落地）
- ❌ 不新增判定逻辑：复用同一个 `TraceEvaluator` 实例，绝不重造（方案 §6.3/§9）。→ **已推翻**：a1 口径自带判定（entry span / 段内任意 isError / 默认慢阈值），**刻意不复用 `TraceEvaluator`**，以保证告警行为零变化。
- ~~不改 `TraceSegmentStorage` 接口的 `accept/snapshot/size`；metrics 是**增量新增**。~~（仍成立，`storeMetricRows/queryMetricRows/aggregateMetricRows` 为新增方法）
- ~~不引第二条写线程、不引第二套有界队列（讨论稿 §6）。~~（仍成立：自持单守护线程 `TraceMetrics-Flush`，直调存储复用写锁）
- ~~不引 HTTP Server；Facade 只反射取数、只返回 JDK 原生类型。~~（仍成立）
- **与 ADR-02 一致**：metrics 只挂 trace 流，不触碰 4 个追加式 sender。
- **与 ADR-03 一致**：`trace_metrics_minute` / `trace_metrics_hour` 是独立小表，不进环形载荷文件；环形只服务 segment payload。

---

## 2. 数据模型（最终 DDL）

> **状态：~~已实现并扩展~~**。分钟表 DDL + 唯一索引 + 桶索引 + `MERGE` + TTL `DELETE` 全部落地于 `H2SqlStatements`（:68-88 建表/索引、:118 MERGE、:167 清理）；列结构与本文一致，**含 `sample_count`**。另新增同结构 `trace_metrics_hour`（:90-110、:120、:169），供 `7d/30d` 走小时分辨率。
> 唯一口径偏差：`endpoint` 注释里的取值来源已从"告警 `EvaluationResult.entryOperation`"改为"**入口段 entry span 的 `operationName`**"（a1，见 §3.3）。

在 `H2SqlStatements` 新增（相对方案 §5.3 增加 `sample_count` 一列，用于解释分位数；其余字段不变）：

```sql
CREATE TABLE IF NOT EXISTS trace_metrics_minute (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    service       VARCHAR(256) NOT NULL,   -- 单体恒为 Config.Agent.SERVICE_NAME
    endpoint      VARCHAR(512) NOT NULL,   -- EvaluationResult.entryOperation，空则 '(unknown)'
    time_bucket   BIGINT NOT NULL,         -- entry span startTime / 60000（整分，UTC 纪元）
    request_count BIGINT NOT NULL,         -- 该桶该 endpoint 的去重 trace 数
    error_count   BIGINT NOT NULL,
    slow_count    BIGINT NOT NULL,
    total_latency BIGINT NOT NULL,         -- Σ durationMs（ms）
    max_latency   INT,
    p50           INT,                     -- 样本不足时为 NULL
    p90           INT,
    p95           INT,
    p99           INT,
    sample_count  INT,                     -- 参与分位数的样本数（≤ request_count）
    create_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_metrics_key    ON trace_metrics_minute(service, endpoint, time_bucket);
CREATE INDEX        IF NOT EXISTS ix_metrics_bucket ON trace_metrics_minute(time_bucket);
```

Upsert（H2 原生，幂等，迟到补写走 UPDATE 分支）— **~~已实现~~**：

```sql
MERGE INTO trace_metrics_minute
  (service, endpoint, time_bucket, request_count, error_count, slow_count,
   total_latency, max_latency, p50, p90, p95, p99, sample_count, update_at)
  KEY (service, endpoint, time_bucket)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP);
```

TTL — **~~已实现（但清理时机与配置项变了，见 §7）~~**：

```sql
DELETE FROM trace_metrics_minute WHERE time_bucket < ?;   -- now - retentionDays
```

> `service` 列保留（对齐 OAP 形状、便于未来导出），单体下恒为常量，不产生额外分桶。**实际取值 = `segment.getService()`，为空兜底 `Config.Agent.SERVICE_NAME`**（`TraceMetricsAggregator.sanitizeService`）。

---

## 3. 采集侧：挂钩点、去重、口径（~~已定稿，但落地时被 §3.1–§3.4 整体推翻~~）

### 3.1 挂钩点

❌ **已取代**：不在 `mergeLogIntoStatMap` 追加 `metricsAggregator.afterTraceMerged(...)`，改挂在 `consume` 逐段喂入。**实际代码**（`LogFileTraceSegmentServiceClient:745-749`）：

```java
// Phase 5 入口段 a1：逐段喂入指标聚合器（非入口段由聚合器内部排除并计数）。
// 跑在 DataCarrier 消费线程上，只改内存桶、零 I/O，不为业务线程增加延迟。
if (metricsAggregator != null) {
    metricsAggregator.onSegment(segment);
}
```

- ✅ 运行在 **DataCarrier 消费线程**；`onSegment` 只改内存桶，**零 I/O**。
- ✅ 受 `plugin.logfilereporter.metrics.enabled` 运行时开关约束（关掉即 `metricsAggregator == null`，零开销）。
- ✅ 与告警**并列且互相独立**（`afterTraceMerged` 只喂告警，:906）。
- ❌ 差异：非入口段（无 `Entry` span）**不计**，只累加 `noEntrySpan` 计数——这正是 a1 排除异步子段/孤段的机制。

<details>
<summary>❌ 已被取代的原文（合并视图挂钩，勿再施工）</summary>

```java
if (traceAlertDispatcher != null && merged != null) {
    traceAlertDispatcher.afterTraceMerged(globalTraceid, merged);
}
if (metricsAggregator != null && merged != null) {          // 新增
    metricsAggregator.afterTraceMerged(globalTraceid, merged);
}
```

</details>

### 3.2 判定复用与"同口径"保证

❌ **已取代（整体不做）**：Phase 5 **不碰告警包**。

- ❌ 不注入 `TraceEvaluator` 到 `AsyncTraceAlertDispatcher`（dispatcher 仍自带 `createIfEnabled()` 内部创建 evaluator）。
- ❌ 不加 `evaluate(snapshot, recordRuleHits)` 重载（`TraceEvaluator:94` 至今仍是**单一签名** `evaluate(snapshot)`）。
- ❌ `EvaluationResult` 不加 `entryStartTimeMs`；`TraceEvaluator` / `EvaluationResult` 可见性未提升。
- ✅ 代价已消除：告警包**零改动**，规则命中计数无双算风险，`validate.ps1` 告警断言全绿（无需额外兜底）。

### 3.3 口径

❌ 下表已被 a1 口径取代。**实际落地口径**（`TraceMetricsAggregator.onSegment`，逐段扫描一遍同时取 entry 与 error）：

| 项 | 实际取值 | 与本文差异 |
| --- | --- | --- |
| `service` | `segment.getService()`，空则 `Config.Agent.SERVICE_NAME` | 兜底来源一致，优先取段自带值 |
| `endpoint` | 段内**第一个 `spanType == Entry`** 的 span 的 `operationName`，空 → `"(unknown)"` | ❌ 不走告警 `entryOperation`；**无 Entry 的孤段直接不计**（不再 fallback 最长 span） |
| `duration` | `entry.getEndTime() - entry.getStartTime()`，负数归 0；退化时才取段内最长 span | ❌ 从"全 span 最大（`maxDurationMs`）"改为"**入口段自身耗时**" |
| `error` | 段内**任意 span** `isError` | ❌ 不再经 `alertTypes.contains(ERROR)` |
| `slow` | `duration >= DEFAULT_SLOW_THRESHOLD_MS`（默认 3000） | ❌ 不再经 `alertTypes.contains(SLOW)`；**Ant 差异化慢规则不参与** |
| `normal` | 不单独建模、不落 `trace_level`，只贡献 count + latency | ✅ 一致 |
| `time_bucket` | `entry.getStartTime() / 60000`（向下取整，UTC 纪元） | ✅ 语义一致（span 起始时间），来源从 `entryStartTimeMs` 改为直接取 span |
| `request_count` | 桶内**入口段数**（a1：一个入口段 = 一次事务） | ❌ 不再按 traceId 去重，见 §3.4 |

> **一致性代价（已知偏差，已文档化）**：指标的 slow/error 判定与告警**不再是同一套规则**（不含 Ant 差异化慢规则、http 状态码错误、`error_ignore_rules` 白名单）。这是换取"告警包零改动"的自觉取舍。

<details>
<summary>❌ 已被取代的原文口径表（勿再作为实现依据）</summary>

| 项 | 取值 | 说明 |
| --- | --- | --- |
| `service` | `Config.Agent.SERVICE_NAME` | 单体常量 |
| `endpoint` | `EvaluationResult.entryOperation` | 空 → `"(unknown)"`；孤 segment（只有 grpc/hikaricp）走 `findPrimaryEntrySpan` 的 fallback（最长 span），与告警同源 |
| `duration` | `EvaluationResult.durationMs` = `maxDurationMs`（跨全部 span 取最大） | **与告警同一口径**，不另立 Entry 口径（避免 §9 "两套口径"） |
| `error` | `alertTypes.contains(ERROR)` | |
| `slow` | `alertTypes.contains(SLOW)` | |
| `normal` | 两者皆无 | 不单独建模，不落 `trace_level` |
| `time_bucket` | `entryStartTimeMs / 60000`（向下取整） | **用 span 起始时间，不用墙钟**；无 span → 用 `now/60000` 并计数 `noStartTime` |
| `request_count` | 桶内**去重 trace 数** | 见 §3.4 |

</details>

### 3.4 计数口径与去重（核心）

❌ **已取代**：a1 口径下**不做 traceId 去重**——每个入口段即一次事务，异步子段天然被"必须含 Entry span"排除。`Map<Long, Map<String, Contribution>>` 的 `traceId` 维度、`MAX_TRACES_PER_BUCKET = 200_000` 上限、`bucketOverflow` 计数**均未实现**。

✅ **实际内存结构**：`Map<timeBucket, Map<endpoint|"*", Accumulator>>`（`TraceMetricsAggregator`），每桶除各 endpoint 行外另写一行全局汇总 `endpoint = "*"`。

<details>
<summary>❌ 已被取代的原文（去重 + 覆盖 + 20 万上限）</summary>

- 内存结构：`Map<Long /*bucket*/, Map<String /*traceId*/, Contribution>>`，`Contribution = {endpoint, durationMs, error, slow}`。
- 首次插入 → 新增一条；**同 traceId 再次事件（多 segment）→ `put` 覆盖**（不 +1）。
- **`request_count` = 桶内 `Map` 的 size**；`error_count/slow_count` = 覆盖后的最终标记计数；`total_latency/max_latency` = 覆盖后的最终 `durationMs` 求和/取最大。
- 覆盖是**单调**的：合并视图只增不减，`maxDurationMs` 不降，error/slow 只可能 false→true。故覆盖 = 最后写入获胜，无需增量修正。
- **已知可接受偏差**：跨分钟边界的迟到段会落入下一桶再计一次 —— 文档化，不做补偿（讨论稿 §3）。
- 桶总贡献数防御上限 `MAX_TRACES_PER_BUCKET = 200_000`，超出丢弃并计数 `bucketOverflow`（单体量级下有冗余裕度）。

</details>

---

## 4. 分位数（v1，锁定）

> **状态：~~已实现并通过单测~~**（`TraceMetricsAggregator.Accumulator.percentiles()`）。以下四条全部落地，数值未变；仅常量名 `MAX_SAMPLES_PER_ENDPOINT` → **`MAX_SAMPLES_PER_KEY = 5000`**（因为 `"*"` 全局键也吃样本配额）。

在**翻转时**按 endpoint 分组、对 `durationMs` 排序后计算，样本来自该桶该 endpoint 的去重 trace：

- ~~**算法**：最近秩（nearest-rank），`rank = ceil(p/100 · n)`，`index = clamp(rank-1, 0, n-1)`。零依赖、精确、约十几行。~~
- ~~**样本上限**：每 endpoint 每桶 `MAX_SAMPLES_PER_ENDPOINT = 5000`；超出用**蓄水池采样**（均匀，固定种子 `RESERVOIR_SEED = 20260924L`），计数 `sampleOverflow`。~~
- ~~**小样本**（相对讨论稿 §8 的细化）：
  - `n ≥ MIN_SAMPLES_FOR_TAILS (=20)` → 四个分位数全算；
  - `n ≥ 1` → **p50 照常算**，`p90/p95/p99` 置 `NULL`；
  - 即低流量 endpoint 仍有 p50，但不会给出失真的尾分位。~~
- ~~`sample_count` 落库 = 实际参与计算的样本数（触发采样时 `< request_count`）。~~
- **v2 演进**（不在本次）：换 Glowroot 式对数分桶直方图；**表结构不变，只换桶内实现**（讨论稿 §5.3）。→ ❌ 仍未做，spec 列为 out of scope，只留 seam。

---

## 5. 分钟翻转与迟到承接（~~已定稿，核心机制已落地~~）

> **状态**：保留窗口 / 定时器 / 迟到红线 / 逐出 **全部已实现**（`LATE_WINDOW_BUCKETS = 3`、`LogFileTraceSegmentServiceClient:90` 的 30s `TraceMetrics-Flush` 守护线程、`flushClosedBuckets`、`lateDropped`）。❌ 仅"**迟到段在窗口内重算覆盖、保证分位数正确**"这一句失效：a1 下一段就是一次独立事务，不存在"同一 trace 覆盖"语义，窗口内的含义是"**继续接纳该分钟的迟到段**"。

> 讨论稿 §5.2 同时写了"清空已落库桶"与"迟到段补写旧桶走 UPDATE"，两者冲突：清空后无法重算分位数。**本文以「保留窗口」方案化解。** → ✅ 化解方案已落地（不清空、窗口内重算覆盖）。

- ~~**桶归属**：由 span 起始时间决定（§3.3），**不是**由事件到达时间决定。~~
- ~~**保留窗口**：内存保留**最近 `BUCKET_RETENTION = 3` 个整分桶**（含当前桶）。~~（常量名 `LATE_WINDOW_BUCKETS`）
- ~~**定时器**：`TraceMetricsAggregator` 自持单守护 `ScheduledExecutorService`，周期 `FLIP_INTERVAL_MS = 30_000`。~~（实际由客户端 `startMetricsFlushTimer()` 持守护线程，聚合器自身不持线程，职责更清晰）
- **每次翻转**：
  1. ~~`currentBucket = now/60000`；~~
  2. ~~对**所有** `bucket ≤ currentBucket - 1` 且仍在保留窗口内的桶：计算分位数 → 组装行 → 批量 `MERGE`（幂等，窗口内重算覆盖）；~~ → **实际补充**：整点时**顺带上一个小时 rollup** 进 `trace_metrics_hour`（分位按 `request_count` 加权平均，计数/总耗时/最大耗时精确）；
  3. ~~逐出 `bucket < currentBucket - BUCKET_RETENTION` 的桶。~~
- ~~**迟到的红线**：事件所属 `bucket < currentBucket - BUCKET_RETENTION` → **丢弃**并计数 `lateDropped`（不新建旧桶）。~~
- ~~**为什么保留 3 分钟**：吸收跨 segment 迟到；超出即放弃，符合"指标尽力而为"。~~

> ~~`FLIP_INTERVAL_MS = 30s < 60s`：保证每个桶在被逐出前至少被翻转写入一次。~~

---

## 6. 落库与线程（锁定）

- ~~翻转线程直接调 `H2TraceSegmentStorage.storeMetrics(List<MetricsRow>)`（**新增方法**），内部 `synchronized(this)` —— **与现有写线程、`snapshot()/size()` 共用同一把锁**，单写者语义不变。~~ → 方法名实际为 **`storeMetricRows(resolution, rows)`**（带分辨率参数，分钟/小时共用）。
- ~~**不新增队列/线程**：翻转是 30s 一次、几十~几百行，直接同步 upsert 可接受；绝不挂在业务线程（翻转在定时器线程）。~~ ✅（仅新增**一个** 30s 守护定时器 `TraceMetrics-Flush`，无第二套有界队列；且线程由客户端 `startMetricsFlushTimer()` 持有，聚合器自身不持线程）
- ~~错误处理同款：`try/catch` + `recordError` + 30s 限速日志，**绝不外抛**；失败计数 `persistErrors`。~~ ✅（聚合器吞异常计 `persistErrors`，存储层 `recordError`）
- ~~**H2 不可用时**（`h2.enabled=false` 或初始化失败）：聚合器**继续内存累计**（供 `statisticMetrics()`），`queryMetrics` 返回空；不报错。~~ ✅
- ~~**关闭序**：`shutdown()` → 聚合器停止定时器 → **最后一次冻结全部保留桶并 upsert** → 存储 `close()`（排空 + fsync）。~~ ✅（`LogFileTraceSegmentServiceClient:651-667`：停 carrier → 告警 → 停 metrics 定时器 → 最后翻转 + rollup → 关存储）

---

## 7. TTL 与保留

❌ **本节配置项与定时器归属均已被取代**：

- ❌ 保留 `plugin.logfilereporter.metrics.retention_days = 30`（初值，可调）→ **未实现该配置项**；改为**类内常量**（spec 决策 p：仅 `enabled` 可配，防配置蔓延）。
- ❌ 清理 SQL 挂进 **Phase 3 引入的 TTL 定时器**（若 Phase 5 先落地，则本步自带同款最小定时器）→ **实际**：由 30s 翻转周期里的 `cleanupMetricsRetention(now)` **顺带执行**（`DELETE ... WHERE time_bucket < ?`，截止值 Java 侧算好）。
- ✅ **实际保留期（常量，不可配）**：`MINUTE_RETENTION_BUCKETS = 2880`（48h）、`HOUR_RETENTION_BUCKETS = 720`（30d），见 `TraceMetricsQuery`。
- ✅ **不**给 metrics 表做"删旧不还空间"的额外处理——本期 H2 **内存模式**，进程重启即整体回收。

---

## 8. 对外 Facade（`SWMetricsUtils`）

> **状态：~~已实现并被大屏实际消费~~**（`validate-h2.ps1` 对 `/inner/sw/metrics` 与 `/inner/sw/metrics/query` 有断言），且**实现超出本文**：多出 `extremeTraces()`（指标→链路下钻）、`aggregate=true`（按 endpoint 汇总）、`resolution` 路由、内存实时窗口并入查询。
> **JSON 契约以 spec 为准**——本文下方 JSON 为早期版本，字段名已有变动。

镜像 `SWTraceParityUtils` 范式（带参反射 + 只返回原生类型）。

**宿主桩**：`agent/demo-app/.../org/apache/skywalking/apm/toolkit/SWMetricsUtils.java` ✅

```java
public static Map<String, Object> statisticMetrics();                  // ✅
public static Map<String, Object> queryMetrics(Map<String, Object> condition);  // ✅
public static Map<String, Object> extremeTraces();                     // ✅（本文未列，spec 后加）
```

**Agent 侧**：
- ~~`LogFileTraceSegmentServiceClient` 新增 `getMetricsStatus()`（委托聚合器）与 `queryMetrics(Map)`（委托 `storage`）。~~ ✅（另加 `getExtremeTraces()`）
- ~~新拦截器 `MetricsExposeInterceptor`（`…core.plugin.logfilereporter`）；新定义 `SWMetricsUtilsInstrumentation`；`skywalking-plugin.def` 追加一行：~~ ✅（`skywalking-plugin.def:20`：`sw-metrics-utils-9.x=…define.SWMetricsUtilsInstrumentation`）

<details>
<summary>❌ 早期 JSON 契约（字段名已被 spec 取代，勿照抄）</summary>

**JSON 契约（早期版本，外部消费者/仪表盘据此编码）**

`statisticMetrics()`：

```json
{
  "enabled": true,
  "storageEnabled": true,
  "flipIntervalMs": 30000,
  "retentionDays": 30,
  "currentBucket": 12345678,
  "buckets": [
    { "service": "demo-app", "endpoint": "GET:/api/order/{id}", "timeBucket": 12345677,
      "bucketStart": "2026-09-24 10:00:00",
      "requestCount": 812, "errorCount": 3, "slowCount": 11,
      "errorRate": 0.0037, "slowRate": 0.0135,
      "avgLatency": 61, "maxLatency": 940,
      "p50": 42, "p90": 120, "p95": 168, "p99": 610, "sampleCount": 812 }
  ],
  "counters": { "lateDropped": 0, "bucketOverflow": 0, "endpointOverflow": 0,
                "sampleOverflow": 0, "noStartTime": 0, "persistErrors": 0, "rowsUpserted": 1234 }
}
```

`queryMetrics(condition)`：`condition` 支持 `endpoint`（可选）、`fromBucket`/`toBucket`（可选，long）、`limit`（可选，默认 200，**上限 1000**）；返回：

```json
{ "rows": [ /* 同上 bucket 行结构 */ ], "count": 812, "truncated": false }
```

</details>

**实际契约与上文的差异（⚠️ 字段名有变）**：

| 上文字段 | 实际字段 | 说明 |
| --- | --- | --- |
| `retentionDays: 30` | `minuteRetentionBuckets: 2880` + `hourRetentionBuckets: 720` | 改为桶数常量，不可配 |
| （无） | `lateWindowBuckets` / `maxQueryPoints` | 迟到窗口 3；查询点数上限 2000 |
| `counters.bucketOverflow` | `counters.endpointOverflow` | 无 20 万 trace 上限，改端点基数上限 |
| `counters.noStartTime` | `counters.noEntrySpan` | 无 span 起点 → 改为"无 Entry span 不计" |
| （无） | `counters.aggregateErrors` / `counters.rollupRows` | 聚合异常 / 小时 rollup 行数 |
| `queryMetrics` 无 `resolution` | `resolution: "minute" \| "hour"` | 缺省按跨度自动路由（≤24h 分钟，否则小时） |
| （无） | `queryMetrics` 支持 `aggregate=true` | 按 endpoint 汇总，避免字典序端点被 LIMIT 饿死 |
| （无） | `extremeTraces()` | 每端点最大耗时那条 trace 的现场（内存、不落库） |

- ✅ `limit` 默认 200 / 上限 1000（`TraceMetricsQuery.LIMIT_DEFAULT` / `LIMIT_MAX`）——**未变**。
- ✅ 保留键 `endpoint = "*"` = 全局汇总行（分位按该桶全部入口段样本算）——**未变**。
- ✅ 默认查询窗口最近 24h。

- ~~**硬约束**：只返回 `Map/List/String/Long/Integer/Boolean`；不暴露任何 Agent 自定义类型；结果集超限截断并置 `truncated=true`。~~ ✅

---

## 9. 配置项（最终键名与默认值）

只新增**两个**配置项（其余为 `static final` 常量，避免配置蔓延）：

| 键 | 默认 | 状态 |
| --- | --- | --- |
| `plugin.logfilereporter.metrics.enabled` | `true` | ~~**已实现**~~（`LogFileReporterPluginConfig.Plugin.LogFileReporter.Metrics.ENABLED = true`；客户端 :519 读取，为 `false` 时 `metricsAggregator` 保持 `null`、零开销） |
| `plugin.logfilereporter.metrics.retention_days` | `30` | ❌ **未实现**（改为常量，见 §7） |

**实际常量对照**（`TraceMetricsAggregator` / `TraceMetricsQuery`，均带注释）：

| 本文常量 | 实际常量 | 值 | 状态 |
| --- | --- | --- | --- |
| `FLIP_INTERVAL_MS` | `METRICS_FLIP_INTERVAL_MS` | 30000 | ✅ |
| `BUCKET_RETENTION` | `LATE_WINDOW_BUCKETS` | 3 | ✅ |
| `MIN_SAMPLES_FOR_TAILS` | `MIN_SAMPLES_FOR_TAILS` | 20 | ✅ |
| `MAX_SAMPLES_PER_ENDPOINT` | `MAX_SAMPLES_PER_KEY` | 5000 | ✅（改名：全局 `"*"` 键也吃配额） |
| `MAX_ENDPOINTS_PER_BUCKET` | `MAX_ENDPOINTS_PER_BUCKET` | 500 | ✅ |
| `UNKNOWN_ENDPOINT` / `OTHER_ENDPOINT` | 同名 | `"(unknown)"` / `"(other)"` | ✅ |
| — | `GLOBAL_ENDPOINT` | `"*"` | ✅ spec 新增 |
| — | `MAX_EXTREME_ENDPOINTS` | 500 | ✅ spec 新增 |
| — | `MINUTE_RETENTION_BUCKETS` / `HOUR_RETENTION_BUCKETS` | 2880 / 720 | ✅ spec 新增 |
| — | `MAX_QUERY_POINTS` | 2000 | ✅ spec 新增 |
| — | `LIMIT_DEFAULT` / `LIMIT_MAX` | 200 / 1000 | ✅ |
| `MAX_TRACES_PER_BUCKET` | — | 200000 | ❌ 未实现（a1 无需） |
| `QUERY_LIMIT_MAX` | — | 1000 | ✅ 改名 `LIMIT_MAX` |

配置类：~~`LogFileReporterPluginConfig.Plugin.LogFileReporter.Metrics { ENABLED, RETENTION_DAYS }`~~ → **实际只有 `Metrics { ENABLED }`**。

---

## 10. 组件与包结构

> **状态：结构已落地，但 `alert/` 三项全部未动**（❌ 告警包零改动是本轮的核心设计约束，不是遗漏）。

```
.../reporter/logfile/
├── LogFileTraceSegmentServiceClient.java   ✅ 改：创建 aggregator + metrics 开关 + onSegment 挂钩 + 30s 翻转定时器
│                                               + rollupPreviousHour + cleanupMetricsRetention
│                                               + getMetricsStatus/queryMetrics/getExtremeTraces；shutdown 序
├── alert/                                    ❌ 未动（刻意）
│   ├── TraceEvaluator.java                   ❌ 未动：仍是单一 evaluate(snapshot)，无 recordRuleHits、无 entryStartTimeMs
│   ├── AsyncTraceAlertDispatcher.java        ❌ 未动：evaluator 仍内部 createIfEnabled()
│   └── TraceSnapshot.java                    ❌ 指标侧未复用（a1 直接读 SegmentObject）
├── metrics/                                ★ 新子包（已落地）
│   ├── TraceMetricsAggregator.java         ✅ 内存桶 + 翻转 + 分位数 + 全局 "*" 行 + extreme 记录
│   ├── MetricsRow.java                     ✅ 行模型（toMap 出原生 Map）
│   ├── MetricsSink.java                    ✅ 落库最小接口（单测注入假实现，不碰 H2）
│   ├── TraceMetricsQuery.java              ✅ 分辨率路由 / limit 钳制 / 保留期常量
│   └── TraceMetricsRollup.java             ✅ 小时 rollup + 降采样 + 按 endpoint 合并
└── storage/
    ├── H2TraceSegmentStorage.java          ✅ 改：新增 storeMetricRows / queryMetricRows / aggregateMetricRows
    └── H2SqlStatements.java                ✅ 改：新增 minute + hour 建表/索引/MERGE/查询/清理/清空常量
```

**demo-app 使用侧**：~~`/inner/sw/metrics` 读口 + 仪表盘页；`SWMetricsUtils` 桩；`validate-h2.ps1` 增断言。~~ ✅ 全部落地（另有 `/inner/sw/metrics/query`、`/inner/sw/metrics/extremes`，以及真实大屏 `dashboards/metrics.html`，五档范围）。

---

## 11. 落地小步（~~8 步全部收口~~，其中 1 步主动取消、2 步被取代）

1. ~~**SQL 常量**：`trace_metrics_minute` 建表/索引/MERGE/TTL/SELECT 进 `H2SqlStatements`。~~ ✅（并扩展出 `trace_metrics_hour`）
2. ~~**`TraceMetricsAggregator`**（纯内存 + 翻转 + 分位数），**单测**：多段 trace 计一次、迟到覆盖、normal 只贡献 count/latency、翻转后桶保留与逐出、小样本 p50-only、采样上限、endpoint 溢出归并。~~ ✅（单测按 a1 口径重写：子段不计、normal 只贡献 count/latency、窗口内迟到/窗口外丢弃、小样本 p50-only、采样上限、endpoint 归并、**全局 `"*"` 分位 = 全样本**、整点 rollup 精确/分位加权）
3. ~~**存储接入**：`H2TraceSegmentStorage.storeMetrics/queryMetrics` + 单测（MERGE 幂等、按条件查询、上限截断）。~~ ✅（`storeMetricRows/queryMetricRows/aggregateMetricRows`）
4. ❌ **告警包改造**：`evaluate(snapshot, recordRuleHits)` + `entryStartTimeMs` + evaluator 外部注入 → **整步取消**。改为 a1 自带判定，告警包零改动（`validate.ps1` 告警断言自然全绿，无需专门兜底）。
5. ~~**客户端挂钩**：`prepare()` 组装、`mergeLogIntoStatMap` 追加调用、`shutdown()` 收尾。~~ ✅（`prepare()` 组装 + 翻转基因删 + `shutdown()` 收尾；❌ 挂钩点从 `mergeLogIntoStatMap` 改为 `consume` 里 `onSegment`）
6. ~~**Facade**：`SWMetricsUtils` 桩 + 拦截器 + instrumentation + `.def` 行；demo-app 读口与页面。~~ ✅（读口与页面均超出本文：`/query`、`/extremes`、`metrics.html` 五档）
7. ~~**TTL**：挂现有定时器。~~ ✅（由 30s 翻转周期顺带清理；❌ 未新增独立定时器、❌ 未加 `retention_days` 配置）
8. ~~**验证**：`validate-h2.ps1` 增"指标行存在、口径自洽（error+slow ≤ request、p50 ≤ p95 ≤ p99、sample_count ≤ request_count）、查询上限截断"；`validate.ps1` 全绿。~~ ✅（并追加：全局 `"*"` 行存在、`metrics.enabled`/`storageEnabled`、五档范围路由可查、limit 截断）

---

## 12. 决策台账（讨论稿 §11 + 新增；~~已按落地结果回写~~）

| # | 决策点 | 锁定值 | **落地实况** |
| --- | --- | --- | --- |
| a | 计数口径 | 同桶 traceId 去重，覆盖写入 | ❌ **已推翻** → a1：每入口段一次事务，不去重 |
| b | 耗时任取 | `maxDurationMs`（与告警同源） | ❌ **已推翻** → 入口段自身 `endTime - startTime`；代价=指标与告警两套判定（已知偏差） |
| c | 分位数采样 | v1 最近秩 + 蓄水池（≤5000/endpoint/桶） | ✅ 落地（`MAX_SAMPLES_PER_KEY=5000`，固定种子） |
| d | normal 计入 | 自动计入 count + latency；不建 normal 维度 | ✅ 落地 |
| e | `SWMetricsUtils` 形态 | `statisticMetrics()` / `queryMetrics(Map)` | ✅ 落地，并加 `extremeTraces()` |
| f | 落库线程 | 复用存储锁，翻转线程直调；不引第二队列 | ✅ 落地（30s 守护定时器，无队列） |
| g | TTL | 30d，挂现有定时器 | ⚠️ **半推翻** → 常量 2880/720 桶，翻转顺带删；无配置项、无独立定时器 |
| h | **判定复用方式** | 同一 `TraceEvaluator` + `evaluate(…,recordRuleHits)` | ❌ **已推翻** → 不复用，a1 自带判定，告警包零改动 |
| i | **迟到承接** | 保留 3 个整分桶，窗口内重算覆盖 | ⚠️ **半落地** → 窗口 3 + 窗口外 `lateDropped` 已落地；"重算覆盖"语义因 a1 不适用 |
| j | **小样本分位数** | p50 始终算；尾分位需 `n≥20` | ✅ 落地 |
| k | **endpoint 基数** | 每桶 ≤500，超出按 request_count 取 top-499 | ⚠️ **半落地** → 上限 500 落地；❌ 归并策略改为**先到先得**并入 `(other)`（`endpointOverflow` 计数） |
| l | **分桶时间源** | span 起始时间（entry span），非墙钟 | ✅ 落地（`entry.getStartTime()/60000`） |
| m | **service 维度** | 恒为 `SERVICE_NAME`（保留列） | ✅ 落地（`segment.getService()` 优先，空则兜底） |
| n | **aggregator 位置** | 新子包 `...reporter.logfile.metrics` | ✅ 落地（且未放 `storage/`，与讨论稿 §6 建议不同） |
| o | **evaluator 注入** | 客户端创建，注入 dispatcher 与 aggregator | ❌ **已取消**（不注入） |
| p | **配置面** | 仅 `enabled` + `retention_days` 可配 | ✅ **完整落地（2026-09-26 确认）** → **只有 `metrics.enabled` 一个配置项**，其余全部写死。团队原则："只留 `enabled` 一个开关，其他写死在代码里，避免配置项越加越多"。**不再把 `retention_days` 或 `MAX_ENDPOINTS_PER_BUCKET` 提为配置项**；要调就改常量重编译 |
| q | **全局汇总行** | （本文无） | ✅ spec 新增：每桶 `endpoint="*"` 行，分位按全样本算 |
| r | **多分辨率** | （本文无） | ✅ spec 新增：小时 rollup，分位按 `request_count` 加权平均（近似） |
| s | **H2 形态** | （本文默认 file 模式思路） | ✅ spec 新增：**仅内存模式**，跨重启不保留 |

---

## 13. 与 ADR / 既有文档的关系

- **ADR-02（两种存储形态）**：遵守。metrics 只挂 trace 流；不改 4 个追加式 sender。✅
- **ADR-03（payload 进环形封顶文件）**：不受影响。`trace_metrics_minute` / `trace_metrics_hour` 是小表，不进环形文件；环形仅服务 segment payload。✅
- **方案 §5.3**：本文相对其**增加 `sample_count` 列**（解释分位数），并明确 upsert/TTL 语句；字段其余不变。✅
- **方案 §6.3（告警下沉）**：~~本文的挂钩点是"告警下沉前"的临时位置（与告警并列）。切流后，聚合器随"合并视图"一起下移进存储，**判定与口径不变**。~~ ❌ 该设想已作废——聚合挂在 `consume` 逐段，天然在"合并视图"之下，不随其下移。
- ~~**内部可见性变更**：`TraceEvaluator` / `EvaluationResult` 提为 `public`。~~ ❌ **未发生**（告警包未改，无需提可见性）。

---

## 14. 已知偏差（文档化，不修）

1. ~~**跨分钟边界的迟到段重复计数**：可能被计两次（落两个桶）；量小、单体少见。~~ ⚠️ 语义已变：a1 下"重复"指同一分钟内的多个入口段各计一次（本就应计）；真正的迟到偏差是**超出 3 桶窗口的段被丢弃**（`lateDropped`）。
2. ~~**未落库分钟丢失**：进程崩溃丢 ≤1 个翻转周期（30s）+ 当前未冻结桶。~~ ⚠️ 内存模式下**更彻底**：进程重启丢全部指标（本期已知取舍）。
3. ~~**分位数是估计值**：采样触发时 `sample_count < request_count`；小样本尾分位为 `NULL`。~~ ✅ 仍成立。
4. ~~**endpoint 溢出归并**：超过 500 的端点并入 `(other)`，明细不可查。~~ ⚠️ 阈值仍为 500，但归并策略是**先到先得**（不是 top-N 按请求数），明细同样不可查。
5. ~~**`(unknown)` 桶**：无 entry/可识别 span 的孤 segment（如只有 hikaricp）会落到该 endpoint。~~ ❌ **已作废**：无 Entry span 的段**直接不计**（`noEntrySpan` 计数），`(unknown)` 仅用于 operationName 为空的入口段。
6. **（spec 新增）指标判定 ≠ 告警判定**：slow 不含 Ant 差异化规则、error 不含 http 状态码与 `error_ignore_rules` 白名单。
7. **（spec 新增）小时分位为近似值**：由该小时分钟行按 `request_count` 加权平均，非真实分位。
8. **（spec 新增）范围分位由桶分位聚合近似**：与原型 A 的模拟口径一致。

---

## 15. 与前端原型的契约对齐

~~`docs/todos/` 下的 trace 指标大屏原型（`agent/demo-app/src/main/resources/static/dashboards/metrics-prototype.html`）所用字段与本文 §8 契约一一对应：…；实现落地时，原型可直接改读 `statisticMetrics()` / `queryMetrics()`。~~

✅ **已落地，且比本文更进一步**：真实大屏 `agent/demo-app/src/main/resources/static/dashboards/metrics.html` 已上线（原型 `metrics-prototype.html` 保留不动），头部五档 `1h/6h/24h/7d/30d` 全部可出图；`1h/6h/24h` 走分钟分辨率、`7d/30d` 走小时分辨率；链路下钻复用既有 trace 读口 + `extremeTraces()`。

---

## 16. 待办：剩余部分（2026-09-25 记录，**本轮不动**）

> Phase 5 主体已收口。以下为**尚未实现**的候选项，择专门时间再动手；动手前请先更新 `.scratch/h2-metrics/spec.md` 或另立 spec，不要直接照本文施工。

### 16.1 建议优先（按价值排序）

| # | 项 | 现状 | 动它要解决什么 |
| --- | --- | --- | --- |
| 1 | **H2 file 模式** | 仅 `jdbc:h2:mem`，进程重启**全部指标归零** | `7d/30d` 档一重启就空，周趋势失去意义。**schema 不变，只换连接**；需同时决定是否放开"本期仅内存"的 spec 约束 |
| 2 | **指标与告警判定统一** | 指标 slow 只用默认阈值（3000ms），不含 Ant 差异化规则；error 不含 http 状态码与 `error_ignore_rules` | 大屏"没慢"而告警发了 SLOW → 用户先怀疑插件。两条路：①统一口径（要碰告警包，与 §3.2 的"零改动"取舍相反，须重新论证）；②UI/文档显式标注口径差异（低成本） |
| 3 | **分位数 v2：对数分桶直方图**<br>⏸️ **2026-09-26 决定：挂起，待测试环境实测后再定** | 仍是样本列表（`MAX_SAMPLES_PER_KEY=5000` 蓄水池），采样时 `sample_count < request_count` | 能修 **2 处**：**采样误差**（不再丢样本，`sample_count` 恒等于 `request_count`）+ **内存**（40KB→0.8KB 每端点·桶，最坏 80MB→1.6MB）。<br>❌ **修不了**"小时 rollup 加权平均近似"——`TraceMetricsRollup` 手里只有分钟行的 p50/p90/p95/p99（`TraceMetricsRollup.java:142-145`），原始样本在分钟翻转时已丢弃，除非给分钟表加列（与"表结构不变"冲突）。<br>**决策依据与测法见** [基数治理方案](h2化-phase5-metrics-端点基数治理方案.md) §5.1 |

> **第 3 项的成立前提（两个收益各有各的门槛，都要先量）**：
>
> | 收益 | 触发门槛 | 怎么看 |
> | --- | --- | --- |
> | 省内存（80MB→1.6MB） | 端点打满 `MAX_ENDPOINTS_PER_BUCKET=500` | `counters.endpointOverflow` |
> | 分位不丢样本 | 单端点单分钟 > 5000 次 | `counters.sampleOverflow` |
>
> - 端点常态几十个时内存仅约 3.5MB；是否打满取决于 `operationName` 是否已归一化——Spring MVC 已是 `GET:/api/order/{id}` 这类映射模式（低基数）；**高基数来源是 404 未映射路径、探针轮询 `/actuator/**`、静态资源、爬虫/扫描器**。
> - **两个计数器都长期为 0 → 这项改造对本系统零价值，可直接划掉。**
> - ⚠️ 抽样门槛按**「分钟桶 × 单个端点」**算（`5000÷60 = 83.3`），**每分钟重置**；**看的是"最热的那一个端点"，不是系统总 QPS**，且一分钟内突发冲进去也算、不需持续跑满。
> - **先在测试环境量一次再决定，别先写代码**——测法见 [基数治理方案](h2化-phase5-metrics-端点基数治理方案.md) §5.1。

### 16.2 低优先

| # | 项 | 现状 |
| --- | --- | --- |
| 4 | endpoint 溢出归并策略 | 500 上限，**先到先得**并入 `(other)`（非 top-N 按请求数）。单体低基数几乎不触发。**✅ 2026-09-26 已定方向 = A1 top-N 最小堆**（A2 Space-Saving / A3 先到先+观察区**已关闭**）；**是否实施仍待** [基数治理方案](h2化-phase5-metrics-端点基数治理方案.md) §5.1 **⓪ 实测 `endpointOverflow`**——长期 = 0 则本项不必做 |
| 5 | ~~`retention_days` 配置化~~ | ❌ **2026-09-26 定：明确不开放。** 已确认原则"只留 `enabled` 一个开关，其余写死防蔓延"（§12 决策 p）。**从待办划掉**——要调 48h/30d 就改 `MINUTE_RETENTION_BUCKETS` 常量重编译 |
| 6 | `ExtremeTraceSelector` 阈值化 | 现口径 = 每端点**最大耗时**那一条（代码已留接口，可扩"超阈值/相对基线"策略） |
| 7 | 第三层分辨率（日桶） | spec 明确 out of scope，仅为 `> 30d` 预留 |

### 16.3 明确**不做**（已被 spec 取代，勿再讨论）

- ❌ 复用 `TraceEvaluator` / 改 `evaluate` 签名 / 加 `entryStartTimeMs` / evaluator 外部注入（§3.2、§12 h/o）
- ❌ 合并视图事件挂钩 `afterTraceMerged`（§3.1）
- ❌ 同桶 traceId 去重与"最后写入获胜"覆盖语义（§3.4）
- ❌ `MAX_TRACES_PER_BUCKET = 200_000` 与 `bucketOverflow` 计数（§3.4）
- ❌ `plugin.logfilereporter.metrics.retention_days` 配置项（§7）
- ❌ 为 metrics 新增独立 TTL 定时器（并入 30s 翻转周期即可）
- ❌ 新增内嵌 HTTP Server / Agent 内可视化框架（页面只由 demo-app 提供）
- ❌ 采样降压策略、Trace 快照 Dump、与 SkyWalking 自带 JVM/meter 指标合并
- ❌ 改动 `metrics-prototype.html` 原型文件本身
