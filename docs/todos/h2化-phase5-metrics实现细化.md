# H2 化 Phase 5：trace-based Metrics 实现细化（决策锁定版）

> **文档定位**：把 [`h2化-phase5-metrics讨论.md`](h2化-phase5-metrics讨论.md) 的推演**落到当前代码**上，逐项锁定决策，使实现时不再出现大的方向变动。本文是讨论稿的**细化**，不是替代；冲突处以本文为准。
>
> **单一场景前提**：定位为**单体应用**（单一 JVM、无 OAP 聚合、无多服务）。故 `service` 恒为 `Config.Agent.SERVICE_NAME`，**没有 instance 维度、没有服务地图**；真正有区分度的维度只有 **endpoint（＝operationName）**。
>
> **本文基于的代码状态**（已核对）：
> - 采集路径：`LogFileTraceSegmentServiceClient.consume` → `mergeLogIntoStatMap`（`…/logfile/LogFileTraceSegmentServiceClient.java:376`），末行调 `traceAlertDispatcher.afterTraceMerged(traceId, merged)`（:395-397）。
> - 判定：`TraceEvaluator.evaluate`（`…/logfile/alert/TraceEvaluator.java:94`），返回 `EvaluationResult{alertTypes, entryOperation, url, durationMs, thresholdMs, errorSpanCount}`（:193-238）；`TraceSpanUtils.maxDurationMs`（`…/alert/TraceSpanUtils.java:71`）。
> - 存储：`storage/` 子包已就绪（`TraceSegmentStorage` / `H2TraceSegmentStorage` / `CappedFileStorage` / `H2SqlStatements`）；H2 `mem` 模式、**独立写线程 + 有界队列 4096、满则丢弃计数**；连接访问走 `synchronized(this)`。
> - 对外 Facade 范式：`LogfileReporterStatusExposeInterceptor`（`status`）、`TraceParityStatusExposeInterceptor` + `SWTraceParityUtils`（带参、可反射取值）。
> - 配置：`LogFileReporterPluginConfig.Plugin.LogFileReporter.{Alert, H2}`（`…/logfile/LogFileReporterPluginConfig.java`）。

---

## 1. 范围与不变式

**做**：在**合并视图事件**上流式聚合 trace 指标 → 内存分钟桶 → 整分批量 upsert 进 `trace_metrics_minute`；新增 `SWMetricsUtils` Facade 暴露只读 `Map`。

**不做 / 不变**：
- normal 明细不落库（Phase 1/ADR-03 既定），指标必须在采集流上算，**不能事后从 H2 明细聚合**。
- 不新增判定逻辑：复用同一个 `TraceEvaluator` 实例，绝不重造（方案 §6.3/§9）。
- 不改 `TraceSegmentStorage` 接口的 `accept/snapshot/size`；metrics 是**增量新增**。
- 不引第二条写线程、不引第二套有界队列（讨论稿 §6）。
- 不引 HTTP Server；Facade 只反射取数、只返回 JDK 原生类型。
- **与 ADR-02 一致**：metrics 只挂 trace 流，不触碰 4 个追加式 sender。
- **与 ADR-03 一致**：`trace_metrics_minute` 是独立小表，不进环形载荷文件；环形只服务 segment payload。

---

## 2. 数据模型（最终 DDL）

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

Upsert（H2 原生，幂等，迟到补写走 UPDATE 分支）：

```sql
MERGE INTO trace_metrics_minute
  (service, endpoint, time_bucket, request_count, error_count, slow_count,
   total_latency, max_latency, p50, p90, p95, p99, sample_count, update_at)
  KEY (service, endpoint, time_bucket)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP);
```

TTL（挂现有 Phase 3 定时器，见 §7）：

```sql
DELETE FROM trace_metrics_minute WHERE time_bucket < ?;   -- now - retentionDays
```

> `service` 列保留（对齐 OAP 形状、便于未来导出），单体下恒为常量，不产生额外分桶。

---

## 3. 采集侧：挂钩点、去重、口径（锁定）

### 3.1 挂钩点

`mergeLogIntoStatMap` 内在告警调用之后**追加一行**（保持旧路径零改动）：

```java
if (traceAlertDispatcher != null && merged != null) {
    traceAlertDispatcher.afterTraceMerged(globalTraceid, merged);
}
if (metricsAggregator != null && merged != null) {          // 新增
    metricsAggregator.afterTraceMerged(globalTraceid, merged);
}
```

- 运行在 **DataCarrier 消费线程**；`afterTraceMerged` 只改内存桶，**零 I/O**。
- 受同一个运行时开关 `isEnableLogfileReporter()` 约束（关掉上报即不产 metrics）。
- 与告警**并列且互相独立**：告警关掉 metrics 照跑（§3.4）。

### 3.2 判定复用与"同口径"保证

- **同一个 `TraceEvaluator` 实例**：在 `LogFileTraceSegmentServiceClient.prepare()` 里创建，注入 `AsyncTraceAlertDispatcher` 与 `TraceMetricsAggregator`（dispatcher 现为自带 `createIfEnabled()` 内部创建 evaluator，需改为**外部注入**）。
- `TraceEvaluator.evaluate` 增加一个内部重载：

  ```java
  EvaluationResult evaluate(TraceSnapshot snapshot, boolean recordRuleHits);
  // 旧签名 evaluate(snapshot) == evaluate(snapshot, true)，告警行为不变
  ```

  - 告警路径：`true`（保留 `TraceAlertMetrics.recordRuleHit` 语义，**规则命中计数不双算**）。
  - metrics 路径：`false`（纯计算，不产生规则命中副作用）。
- `EvaluationResult` **新增字段 `entryStartTimeMs`**（分桶用；`evaluate` 里取 `entrySpan.getStartTime()`，无 span 时为 `-1`）。
- 可见性：`TraceEvaluator`、`evaluate(…,boolean)`、`EvaluationResult` 提升为 `public`（仅 Agent 内部跨包使用，**不越 Business 边界**，不违反方案 §8）。

> **代价说明**：这是对告警包的**内部改造**（签名/可见性/一处新增字段）。告警的**对外行为与计数器必须零变化**，由现有告警单测 + `validate.ps1` 兜底。之所以不"让 metrics 自己 new 一个 evaluator"：那会二次调用 `evaluate` 从而**双算规则命中计数**，污染 `TraceAlertMetrics`。

### 3.3 口径（每项锁定）

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

### 3.4 计数口径与去重（核心）

- 内存结构：`Map<Long /*bucket*/, Map<String /*traceId*/, Contribution>>`，`Contribution = {endpoint, durationMs, error, slow}`。
- 首次插入 → 新增一条；**同 traceId 再次事件（多 segment）→ `put` 覆盖**（不 +1）。
- **`request_count` = 桶内 `Map` 的 size**；`error_count/slow_count` = 覆盖后的最终标记计数；`total_latency/max_latency` = 覆盖后的最终 `durationMs` 求和/取最大。
- 覆盖是**单调**的：合并视图只增不减，`maxDurationMs` 不降，error/slow 只可能 false→true。故覆盖 = 最后写入获胜，无需增量修正。
- **已知可接受偏差**：跨分钟边界的迟到段会落入下一桶再计一次 —— 文档化，不做补偿（讨论稿 §3）。
- 桶总贡献数防御上限 `MAX_TRACES_PER_BUCKET = 200_000`，超出丢弃并计数 `bucketOverflow`（单体量级下有冗余裕度）。

---

## 4. 分位数（v1，锁定）

在**翻转时**按 endpoint 分组、对 `durationMs` 排序后计算，样本来自该桶该 endpoint 的去重 trace：

- **算法**：最近秩（nearest-rank），`rank = ceil(p/100 · n)`，`index = clamp(rank-1, 0, n-1)`。零依赖、精确、约十几行。
- **样本上限**：每 endpoint 每桶 `MAX_SAMPLES_PER_ENDPOINT = 5000`；超出用**蓄水池采样**（均匀，固定种子），计数 `sampleOverflow`。
- **小样本**（相对讨论稿 §8 的细化）：
  - `n ≥ MIN_SAMPLES_FOR_TAILS (=20)` → 四个分位数全算；
  - `n ≥ 1` → **p50 照常算**，`p90/p95/p99` 置 `NULL`；
  - 即低流量 endpoint 仍有 p50，但不会给出失真的尾分位。
- `sample_count` 落库 = 实际参与计算的样本数（触发采样时 `< request_count`）。
- **v2 演进**（不在本次）：换 Glowroot 式对数分桶直方图；**表结构不变，只换桶内实现**（讨论稿 §5.3）。

---

## 5. 分钟翻转与迟到承接（锁定，消除讨论稿的自相矛盾）

> 讨论稿 §5.2 同时写了"清空已落库桶"与"迟到段补写旧桶走 UPDATE"，两者冲突：清空后无法重算分位数。**本文以「保留窗口」方案化解。**

- **桶归属**：由 span 起始时间决定（§3.3），**不是**由事件到达时间决定。
- **保留窗口**：内存保留**最近 `BUCKET_RETENTION = 3` 个整分桶**（含当前桶）。
- **定时器**：`TraceMetricsAggregator` 自持单守护 `ScheduledExecutorService`，周期 `FLIP_INTERVAL_MS = 30_000`。
- **每次翻转**：
  1. `currentBucket = now/60000`；
  2. 对**所有** `bucket ≤ currentBucket - 1` 且仍在保留窗口内的桶：计算分位数 → 组装行 → 批量 `MERGE`（幂等，迟到段在同窗口内会**重算并覆盖**，分位数保持正确）；
  3. 逐出 `bucket < currentBucket - BUCKET_RETENTION` 的桶。
- **迟到的红线**：事件所属 `bucket < currentBucket - BUCKET_RETENTION` → **丢弃**并计数 `lateDropped`（不新建旧桶）。
- **为什么保留 3 分钟**：吸收跨 segment 迟到（同一 trace 的后续 segment 通常秒级内到达）；超出即放弃，符合"指标尽力而为"。

> `FLIP_INTERVAL_MS = 30s < 60s`：保证每个桶在被逐出前至少被翻转写入一次。

---

## 6. 落库与线程（锁定）

- 翻转线程直接调 `H2TraceSegmentStorage.storeMetrics(List<MetricsRow>)`（**新增方法**），内部 `synchronized(this)` —— **与现有写线程、`snapshot()/size()` 共用同一把锁**，单写者语义不变。
- **不新增队列/线程**：翻转是 30s 一次、几十~几百行，直接同步 upsert 可接受；绝不挂在业务线程（翻转在定时器线程）。
- 错误处理同款：`try/catch` + `recordError` + 30s 限速日志，**绝不外抛**；失败计数 `persistErrors`。
- **H2 不可用时**（`h2.enabled=false` 或初始化失败）：聚合器**继续内存累计**（供 `statisticMetrics()`），`queryMetrics` 返回空；不报错。
- **关闭序**：`shutdown()` → 聚合器停止定时器 → **最后一次冻结全部保留桶并 upsert** → 存储 `close()`（排空 + fsync）。

---

## 7. TTL 与保留

- 保留 `plugin.logfilereporter.metrics.retention_days = 30`（初值，可调）。
- 清理 SQL 挂进 **Phase 3 引入的 TTL 定时器**（若 Phase 5 先落地，则本步自带同款最小定时器）。
- **不**给 `trace_metrics_minute` 做"删旧不还空间"的额外处理：该表行小且量级低（每分钟 ≤ endpoint 数行），漂移可忽略（与 `trace_segment` 的处置不同，后者见方案 §5.4 的补充）。

---

## 8. 对外 Facade（`SWMetricsUtils`）

镜像 `SWTraceParityUtils` 范式（带参反射 + 只返回原生类型）。

**宿主桩**：`agent/demo-app/.../org/apache/skywalking/apm/toolkit/SWMetricsUtils.java`

```java
public static Map<String, Object> statisticMetrics();
public static Map<String, Object> queryMetrics(Map<String, Object> condition);
```

**Agent 侧**：
- `LogFileTraceSegmentServiceClient` 新增 `getMetricsStatus()`（委托聚合器）与 `queryMetrics(Map)`（委托 `storage`）。
- 新拦截器 `MetricsExposeInterceptor`（`…core.plugin.logfilereporter`）；新定义 `SWMetricsUtilsInstrumentation`；`skywalking-plugin.def` 追加一行：

  `sw-metrics-utils-9.x=org.apache.skywalking.apm.agent.core.plugin.logfilereporter.define.SWMetricsUtilsInstrumentation`

**JSON 契约（锁定，外部消费者/仪表盘据此编码）**

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

**硬约束**：只返回 `Map/List/String/Long/Integer/Boolean`；不暴露任何 Agent 自定义类型；结果集超限截断并置 `truncated=true`。

---

## 9. 配置项（最终键名与默认值）

只新增**两个**配置项（其余为 `static final` 常量，避免配置蔓延）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `plugin.logfilereporter.metrics.enabled` | `true` | 是否启用聚合 |
| `plugin.logfilereporter.metrics.retention_days` | `30` | 聚合表保留天数 |

常量（在 `TraceMetricsAggregator` 内，带注释）：`FLIP_INTERVAL_MS=30000`、`BUCKET_RETENTION=3`、`MAX_TRACES_PER_BUCKET=200000`、`MAX_SAMPLES_PER_ENDPOINT=5000`、`MAX_ENDPOINTS_PER_BUCKET=500`、`MIN_SAMPLES_FOR_TAILS=20`、`QUERY_LIMIT_MAX=1000`、`UNKNOWN_ENDPOINT="(unknown)"`、`OTHER_ENDPOINT="(other)"`。

配置类新增：`LogFileReporterPluginConfig.Plugin.LogFileReporter.Metrics { ENABLED, RETENTION_DAYS }`。

---

## 10. 组件与包结构

```
.../reporter/logfile/
├── LogFileTraceSegmentServiceClient.java   (改：创建/注入 evaluator、aggregator；合并事件追加调用；getMetricsStatus/queryMetrics；shutdown 序)
├── alert/
│   ├── TraceEvaluator.java                 (改：evaluate(snapshot, recordRuleHits) + entryStartTimeMs；public)
│   ├── AsyncTraceAlertDispatcher.java      (改：evaluator 改为构造注入；行为不变)
│   └── TraceSnapshot.java                  (已 public，复用)
├── metrics/                                ★ 新子包
│   └── TraceMetricsAggregator.java         (内存桶 + 翻转 + 分位数 + 落库编排 + 快照)
└── storage/
    ├── H2TraceSegmentStorage.java          (改：新增 storeMetrics / queryMetrics)
    └── H2SqlStatements.java                (改：新增建表/索引/MERGE/TTL/SELECT 常量)
```

**demo-app 使用侧**：`/inner/sw/metrics` 读口 + 仪表盘页；`SWMetricsUtils` 桩；`validate-h2.ps1` 增断言。

---

## 11. 落地小步（每步可独立提交）

1. **SQL 常量**：`trace_metrics_minute` 建表/索引/MERGE/TTL/SELECT 进 `H2SqlStatements`。
2. **`TraceMetricsAggregator`**（纯内存 + 翻转 + 分位数），**单测**：多段 trace 计一次、迟到覆盖、normal 只贡献 count/latency、翻转后桶保留与逐出、小样本 p50-only、采样上限、endpoint 溢出归并。
3. **存储接入**：`H2TraceSegmentStorage.storeMetrics/queryMetrics` + 单测（MERGE 幂等、按条件查询、上限截断）。
4. **告警包改造**：`evaluate(snapshot, recordRuleHits)` + `entryStartTimeMs` + evaluator 外部注入；**告警单测 + `validate.ps1` 必须零行为变化**。
5. **客户端挂钩**：`prepare()` 组装、`mergeLogIntoStatMap` 追加调用、`shutdown()` 收尾。
6. **Facade**：`SWMetricsUtils` 桩 + 拦截器 + instrumentation + `.def` 行；demo-app 读口与页面。
7. **TTL**：挂现有定时器。
8. **验证**：`validate-h2.ps1` 增"指标行存在、口径自洽（error+slow ≤ request、p50 ≤ p95 ≤ p99、sample_count ≤ request_count）、查询上限截断"；`validate.ps1` 全绿。

---

## 12. 决策台账（讨论稿 §11 全部锁定 + 新增）

| # | 决策点 | **锁定值** | 理由 / 若改动的代价 |
| --- | --- | --- | --- |
| a | 计数口径 | 同桶 traceId 去重，覆盖写入；`request_count`=去重 trace 数 | 讨论稿推荐；改口径会改所有历史行语义 |
| b | 耗时任取 | `EvaluationResult.durationMs`（`maxDurationMs`，与告警同源） | 同口径优先；另立 Entry 口径会引入"两套口径"风险（§9） |
| c | 分位数采样 | v1 最近秩 + 蓄水池（≤5000/endpoint/桶） | 表结构不变即可演进 v2 直方图 |
| d | normal 计入 | 自动计入 count + latency；不建 normal 维度 | 零成本；改则需新增列/语义 |
| e | `SWMetricsUtils` 形态 | `statisticMetrics()` / `queryMetrics(Map)`，契约见 §8 | 改契约会破坏外部消费者 |
| f | 落库线程 | 复用存储锁，翻转线程直调 `storeMetrics`；不引第二队列/线程 | 少一套异步骨架 |
| g | TTL | 30d，挂现有定时器 | — |
| h | **判定复用方式** | 同一 `TraceEvaluator` 实例 + `evaluate(…,recordRuleHits)` | 防规则命中计数双算；改为各自 new evaluator 会污染告警指标 |
| i | **迟到承接** | 保留 3 个整分桶，窗口内重算覆盖；窗口外丢弃计数 | 解决讨论稿"清空 vs 补写"矛盾；改窗口大小只是常量 |
| j | **小样本分位数** | p50 始终算；p90/p95/p99 需 `n≥20` | 低流量 endpoint 也有中位数；阈值是常量 |
| k | **endpoint 基数** | 每桶 ≤500，超出按 request_count 取 top-499，其余并入 `(other)` | 单体端点有界；保总量正确 |
| l | **分桶时间源** | span 起始时间（entry span），非墙钟 | 与 OAP `time_bucket=start_time` 语义一致 |
| m | **service 维度** | 恒为 `SERVICE_NAME`（保留列） | 单体无需 instance 维度 |
| n | **aggregator 位置** | 新子包 `...reporter.logfile.metrics` | 与 storage/alert 解耦 |
| o | **evaluator 注入** | 客户端创建，注入 dispatcher 与 aggregator | 唯一真源 |
| p | **配置面** | 仅 `enabled` + `retention_days` 可配，其余常量 | 防配置蔓延（方案 §8 极简优先） |

---

## 13. 与 ADR / 既有文档的关系

- **ADR-02（两种存储形态）**：遵守。metrics 只挂 trace 流；不改 4 个追加式 sender。
- **ADR-03（payload 进环形封顶文件）**：不受影响。`trace_metrics_minute` 是小表，不进环形文件；环形仅服务 segment payload。
- **方案 §5.3**：本文相对其**增加 `sample_count` 列**（解释分位数），并明确 upsert/TTL 语句；字段其余不变。
- **方案 §6.3（告警下沉）**：本文的挂钩点是"告警下沉前"的临时位置（与告警并列）。切流后，聚合器随"合并视图"一起下移进存储（届时若用装饰器，聚合调用同址迁移），**判定与口径不变**。
- **内部可见性变更**：`TraceEvaluator` / `EvaluationResult` 提为 `public`。这是**插件内**可见性，不跨 Business 边界，不违反方案 §8 的 ClassLoader 约束。

---

## 14. 已知偏差（文档化，不修）

1. **跨分钟边界的迟到段重复计数**：可能被计两次（落两个桶）；量小、单体少见。
2. **未落库分钟丢失**：进程崩溃丢 ≤1 个翻转周期（30s）+ 当前未冻结桶；不引 WAL（指标尽力而为；error/slow 明细有 H2 兜底）。
3. **分位数是估计值**：采样触发时 `sample_count < request_count`；小样本尾分位为 `NULL`。
4. **endpoint 溢出归并**：超过 500 的端点并入 `(other)`，明细不可查。
5. **`(unknown)` 桶**：无 entry/可识别 span 的孤 segment（如只有 hikaricp）会落到该 endpoint。

---

## 15. 与前端原型的契约对齐

`docs/todos/` 下的 trace 指标大屏原型（`agent/demo-app/src/main/resources/static/dashboards/metrics-prototype.html`）所用字段与本文 §8 契约一一对应：`request_count / error_count / slow_count / errorRate / slowRate / avgLatency / maxLatency / p50/p90/p95/p99`；存储/数据面卡片对应 §6/§7 与既有 `getParityStatus`。实现落地时，原型可直接改读 `statisticMetrics()` / `queryMetrics()`。
