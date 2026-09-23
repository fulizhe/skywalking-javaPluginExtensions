# H2 化 Phase 5：trace-based Metrics 实现方案（讨论稿）

> **文档定位**：基于 `docs/todos/h2化-统一方案.md`（§4 Phase 5、§5.3、§5.4、§6.3、§7.1、§9、§11）与当前代码的**独立讨论稿**。仅方案推演，**未实现、未落任何代码**。供下一步详细思考与拍板。

---

## 1. 一条硬约束先行：为什么必须流式聚合

约束 #11 + §1.4 已定：**normal 明细不落 H2**（只留内存热层，纯 FIFO）。

由此推出两条推论：

1. **metrics 不能事后从 H2 明细聚合**——normal 的明细根本不在 H2。
2. **metrics 的唯一挂钩点 = 采集流上的合并视图事件**，即 `LogFileTraceSegmentServiceClient.mergeLogIntoStatMap` 中 `traceAlertDispatcher.afterTraceMerged(traceId, mergedMap)` 那一行（：395-397）。

> **结论：挂钩点选"合并视图事件"，不是"H2 明细写入"。** Phase 2 收窄后 error/slow 才到 H2，normal 到不了；若挂在 H2 写路径上，normal 的指标直接丢。

## 2. 判定逻辑全现成：metrics 零新增判定

合并视图事件上已有的 `TraceSnapshot.fromMergedMap(traceId, mergedMap)` + `TraceEvaluator.evaluate(snapshot)` 已产出 metrics 需要的全部字段：

| 聚合字段 | 来源（`EvaluationResult` / snapshot） |
| --- | --- |
| endpoint（维度） | `entryOperation` = 首个 Entry span 的 operationName（`TraceSpanUtils.findPrimaryEntrySpan`） |
| 耗时 | `durationMs` = `TraceSpanUtils.maxDurationMs(snapshot)`（跨全部 span 取最大） |
| error 计数 | `alertTypes.contains(ERROR)` |
| slow 计数 | `alertTypes.contains(SLOW)` |
| service（维度） | `snapshot.getService()`，兜底 `Config.Agent.SERVICE_NAME`（与告警同款） |

**normal 计入是免费的**：alertTypes 为空 → 不产 error/slow 计数，但 request_count 与 latency 分布照收。**只要聚合器挂在整个采集流上，normal 天然贡献该贡献的部分。**

聚合核心：

```text
合并视图事件(traceId, mergedMap)
  → TraceSnapshot.fromMergedMap(...)
  → TraceEvaluator.evaluate(snapshot)     // 复用，不重造；§6.3/§9 强制同口径
  → 按 (service, endpoint, 分钟桶) 累加
```

## 3. 计数口径：一个 trace 一个分钟桶只计一次

`afterTraceMerged` 是**每段触发**的（跨多 segment 的 trace 触发 N 次），直接每事件 +1 会把请求数算爆。

**推荐口径**：

- **同一个 traceId 在同一个分钟桶内只计一次**：聚合器内存维护 `Set<traceId>`（按当前桶去重）。
- 后到段（迟到 segment）**覆盖更新**而非叠加：latency/error/slow 取该 trace 最终评估结果，`MERGE` 时 UPDATE 数值、不 +1 count。
- **已知可接受偏差**：跨分钟边界的迟到段会落入下一个桶再计一次——迟到量小、单体场景少见，记录为文档化偏差，不做完美补偿。

**request_count 定义**："该分钟内完成、端到端合并后评估过的 trace 数"，与告警评估同一时刻、同一视图、同源。

## 4. 耗时口径：跟告警一致，不另立口径

对 §11 的"Entry vs max span"：

> **推荐 = 告警口径 `maxDurationMs`**。理由不是"Entry 更准"，而是**同口径 > 最优口径**——E2E 耗时、告警 slow 判定、metrics 的 latency 分布来自同一个 `EvaluationResult`，后续 tuning 只动一处，§9 风险表"两套口径"不产生。

## 5. 分钟桶的聚合与翻转（核心难点）

表结构（§5.3）为 `(service, endpoint, time_bucket, request_count, error_count, slow_count, total_latency, max_latency, p50, p90, p95, p99)` + 唯一键 `(service, endpoint, time_bucket)`。

**唯一不能在 SQL 里增量算的是分位数——必须持有样本。**

### 5.1 内存累计（分钟桶）

```text
内存 Map<service + endpoint + minuteBucket, Accumulator>
  Accumulator: requestCount, errorCount, slowCount, totalLatency, maxLatency,
               List<Long> latencySamples   // 分位数来源
```

- 桶归属用 trace 的 **startTime / 60_000**（与 OAP time_bucket=start_time 语义一致）。
- 样本列表设**容量防御**：单桶上限（如 5000），超出后随机/等比丢 tail——分位数是估计值，丢 tail 无伤大雅。单机单体 QPS 撑不起内存压力，此防御只是保险丝。

### 5.2 分钟翻转：谁触发、何时落库

整分定时器（复用现有 TTL 定时任务节奏，或自身 `ScheduledExecutorService`）：

- 每到整分，冻结 `bucket < currentMinute` 的桶：
  - 排序 samples → p50/p90/p95/p99
  - 组装行 → 批量 `MERGE INTO trace_metrics_minute ... KEY(service, endpoint, time_bucket)`（H2 原生 upsert，迟到段补写旧桶走 UPDATE 分支）
  - 清空已落库桶
- **滞后一个整分才落库**：给一个桶至少 1 分钟窗口承接迟到段，减少跨桶重复计数。

### 5.3 分位数实现的分层

- **v1（推荐）**：原始样本 + 翻转时排序。零依赖、精确、代码十几行，满足约束 #8（极简优先）。
- **v2（演进）**：QPS 实测撑不住时换 Glowroot 式**对数分桶直方图**（常量内存 + 估算）。**表结构不动，只换 Accumulator 内部实现。**

## 6. 与存储层的关系

- 聚合器是**内存累加器**，落库走存储层。
- 位置：`storage/` 子包，命名如 `TraceMetricsAggregator`（聚合计算）；建表 SQL + upsert SQL + TTL DELETE 进 `H2SqlStatements`。
- 落库批量 = "每整分一次、每批 = 冻结桶数"，量级很小（几十~几百行/分）→ 直接复用 H2 现有写线程 + `synchronized` 片段（`storeLog` 同款）。**不引第二条写线程、不引第二套有界队列**——别为优雅引入第二套异步骨架。
- 错误处理同款：catch + `recordError` + 限速日志，绝不外抛。"监控只是助力"。
- TTL 30d：`DELETE FROM trace_metrics_minute WHERE time_bucket < ?` 挂进现有 TTL 定时器顺带跑（§5.4）。

## 7. 对外 Facade：SWMetricsUtils

完全复刻 `SWLogfileReporterUtils` 范式：

| 方法 | 职责 | 数据来源 |
| --- | --- | --- |
| `Map statisticMetrics()` | 进程内**当前分钟**累计快照（实时感） | 反射取聚合器内存桶 → `Map`（JDK 原生） |
| `Map queryMetrics(Map condition)` | 历史分钟桶查询（service/endpoint/时间范围） | 反射取存储层 → 读 H2 聚合表 → `List<Map>` |

- 宿主桩放 business 侧 toolkit（与 `SWLogfileReporterUtils` 并列），运行期由新拦截器 `LogfileReporterMetricsExposeInterceptor` 接管。
- 跨 PluginClassLoader 走 `ServiceManager` + 反射取数——沿用 `LogfileReporterStatusExposeInterceptor` 每一步，特别是"必须用反射、别强转"的警告。
- 聚合计算全在 Agent 侧 Metrics Service，工具类只做 Facade（§7.1）。
- 防御：`queryMetrics` 结果集设上限（如 1000 行），超限截断 + 计数。

## 8. 风险与已知取舍

| 取舍 | 决策 | 理由 |
| --- | --- | --- |
| 进程崩溃丢"未落库分钟桶" | **接受**，不引入 WAL | 至多丢约 1 分钟窗口的 metrics；error/slow 明细有 H2 兜底，指标本来就是尽力而为 |
| 迟到段跨桶重复计数 | **文档化为已知偏差** | 迟到量小、单体场景少见 |
| 小样本分位数的估计偏差（<10 样本） | 落库时**置空分位数列** | 避免小样本下的 p90/p95/p99 失真 |
| endpoint 维度桶数量爆炸 | 超出上限按 LRU 冻结 | 与 `KeyedLocalStore` FIFO 哲学一致 |

## 9. 落地小步建议（将来动手的顺序）

1. 表 + SQL 常量（`trace_metrics_minute` DDL + `MERGE` + TTL DELETE）进 `H2SqlStatements`。
2. `TraceMetricsAggregator`（内存桶 + 分钟翻转 + 分位数 + 落库编排），单测：多段 trace 计一次、迟到段覆盖、normal 只贡献 count/latency、翻转后桶清空、小样本分位数缺失。
3. 客户端加一行 `metricsAggregator.afterTraceMerged(traceId, merged)`（与告警并列），切流后随合并视图一起下移（§6.3）。
4. `SWMetricsUtils` 桩 + 拦截器，接进 `statisticStatus` 同款读口。
5. TTL 定时任务加指标清理。

## 10. 一句话结论

> **复用 `TraceEvaluator.evaluate` 在合并视图事件上累加内存分钟桶，整分批量 upsert 进 `trace_metrics_minute`，Facade 走 SWMetricsUtils 反射范式。** 难点只有分位数和"trace 计一次"的口径，分别用样本排序 v1 和同桶 traceId 去重化解。

## 11. 待详细思考/拍板的点（原方案 §11 Phase 5 行）

| # | 待决 | 本讨论稿的推荐 | 备注 |
| --- | --- | --- | --- |
| a | 计数口径 | 同桶 traceId 去重，一次/分钟桶 | 迟到段跨桶可接受 |
| b | 耗时任取（Entry vs max span） | `maxDurationMs`（与告警同源） | 同口径优先 |
| c | 分位数采样 | v1 全样本排序；v2 Glowroot 直方图 | 表结构不变 |
| d | normal 计入方式 | 自动计入 request_count + latency 分布 | 零成本 |
| e | SWMetricsUtils 形态 | `statisticMetrics()` / `queryMetrics(Map)` | 复刻 SWLogfileReporterUtils |
| f | 落库线程 | 复用现有写线程 + synchronized，整分批量 | 不新增异步骨架 |
| g | TTL | 30d，`time_bucket < now-30d` | 挂现有 TTL 定时器 |