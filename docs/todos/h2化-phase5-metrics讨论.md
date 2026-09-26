# H2 化 Phase 5：trace-based Metrics 实现方案（讨论稿）

> **实现状态（2026-09-25 核对代码后回写）**：**Phase 5 已实现完成**（spec 票 01–06 + 30d；`logfile-reporter-plugin` 单测 132 全绿，`validate.ps1` / `validate-h2.ps1` 全绿）。本文作为**推演历史**保留，逐条标注如下。
>
> **⚠️ 图例**：~~删除线~~ = 已实现并验证；`❌` = 已被 spec 取代、**永不实现**（主动推翻，不是漏做）；无标注 = 真·未实现，待评估。
>
> **文档定位**：基于 `docs/todos/h2化-统一方案.md`（§4 Phase 5、§5.3、§5.4、§6.3、§7.1、§9、§11）与当时代码的**独立讨论稿**；原讨论阶段标注为“未实现”。**已细化**：[`h2化-phase5-metrics实现细化.md`](h2化-phase5-metrics实现细化.md)（同批回写）。冲突处以 spec 为准。
>
> **⚠️ 2026-09-24 路线调整（已落地）**：本文 **§2 判定复用**、**§3 traceId 去重**、**§4 耗时口径**、**§5.2「清空已落库桶」**、**§8 部分风险项**、**§9 第 3 步**、**§10 一句话结论** 已被推翻——实际落地为 **入口段 a1 + H2 内存模式 + 多分辨率（分钟 + 小时 rollup）**，且**不改告警包**。**唯一权威 spec：`.scratch/h2-metrics/spec.md`**；统计口径以入口段口径讨论（`[已实现]h2化-phase5-metrics-口径与插入点讨论.md`）的 a1 为准。

---

## 1. 一条硬约束先行：为什么必须流式聚合

约束 #11 + §1.4 已定：**normal 明细不落 H2**（只留内存热层，纯 FIFO）。

由此推出两条推论：

1. ~~**metrics 不能事后从 H2 明细聚合**——normal 的明细根本不在 H2。~~ ✅ 仍然成立，且是本方案的根本动因。
2. ❌ **metrics 的唯一挂钩点 = 采集流上的合并视图事件**，即 `LogFileTraceSegmentServiceClient.mergeLogIntoStatMap` 中 `traceAlertDispatcher.afterTraceMerged(traceId, mergedMap)` 那一行（：395-397）。→ **实际挂钩点是 `consume` 里的逐段喂入** `metricsAggregator.onSegment(segment)`（`LogFileTraceSegmentServiceClient:745-749`）。

> ~~**结论：挂钩点选"合并视图事件"，不是"H2 明细写入"。** Phase 2 收窄后 error/slow 才到 H2，normal 到不了；若挂在 H2 写路径上，normal 的指标直接丢。~~
> ⚠️ **前半句成立、后半句已被取代**：确实不能挂 H2 写路径（✅ 仍对），但最终选了**比合并视图更上游**的 `consume` 逐段入口——这样连合并视图的不完备性都不用依赖。

## 2. 判定逻辑全现成：metrics 零新增判定

❌ **本节整体已被取代**：a1 口径**不复用 `TraceEvaluator`**，改为在 `TraceMetricsAggregator.onSegment` 内自带判定。取舍见细化稿 §3.2/§3.3。

<details>
<summary>❌ 已被取代的原文（复用 TraceEvaluator，勿再施工）</summary>

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

</details>

✅ **实际聚合核心**：

```text
原始 segment(SegmentObject)                      // consume 消费线程
  → 段内有 spanType == Entry ? 否 : noEntrySpan++  // 排除异步子段/孤段
  → entry span: operationName / (endTime-startTime) / startTime 分桶
  → 段内任意 span.isError → error
  → duration >= 默认慢阈值 → slow
  → 按 (service, endpoint|"*", 分钟桶) 累加        // 无 traceId 维度
```

## 3. 计数口径：一个 trace 一个分钟桶只计一次

❌ **本节已被取代**：a1 口径下**不做 traceId 去重**。

- ❌ 聚合器内存维护 `Set<traceId>` 按桶去重 → **未实现**；`Map<timeBucket, Map<endpoint|"*", Accumulator>>`，无 traceId 维度。
- ❌ 后到段覆盖更新 → 不适用（每个入口段就是一次独立事务）。
- ❌ `request_count` = "端到端合并后评估过的 trace 数" → **实际** = "该分钟内**入口段**数"。
- ✅ **迟到处理仍保留**（换了个理由）：`bucket < now - 3` 的段丢弃并计 `lateDropped`（细化稿 §5）。
- ✅ **normal 天然计入**：段无 error/slow 标记时只贡献 count + latency。

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

- ~~桶归属用 trace 的 **startTime / 60_000**（与 OAP time_bucket=start_time 语义一致）。~~ ✅（用 entry span 的 `startTime`）
- ~~样本列表设**容量防御**：单桶上限（如 5000），超出后随机/等比丢 tail——分位数是估计值，丢 tail 无伤大雅。单机单体 QPS 撑不起内存压力，此防御只是保险丝。~~ ✅ 落地为**蓄水池采样**（均匀、固定种子 `RESERVOIR_SEED=20260924L`）比"等比丢 tail"更准；`MAX_SAMPLES_PER_KEY=5000`，溢出计 `sampleOverflow`。
- ✅ **额外**：每桶除各 endpoint 行外，另写一行 `endpoint="*"` 全局汇总（spec 新增，分位按全样本算）。

### 5.2 分钟翻转：谁触发、何时落库

整分定时器（复用现有 TTL 定时任务节奏，或自身 `ScheduledExecutorService`）：

- ~~每到整分，冻结 `bucket < currentMinute` 的桶：~~
  - ~~排序 samples → p50/p90/p95/p99~~ ✅
  - ~~组装行 → 批量 `MERGE INTO trace_metrics_minute ... KEY(service, endpoint, time_bucket)`（H2 原生 upsert，迟到段补写旧桶走 UPDATE 分支）~~ ✅
  - ❌ ~~清空已落库桶~~ → **已取代**：改为**保留最近 3 个整分桶**（`LATE_WINDOW_BUCKETS=3`），窗口内重算覆盖、超窗才逐出（否则清空后无法重算分位数——这正是细化稿 §5 要消除的自相矛盾）。
- ~~**滞后一个整分才落库**：给一个桶至少 1 分钟窗口承接迟到段，减少跨桶重复计数。~~ ✅ 滞后 1 个整分落库 + 再保留 2 个桶吸收迟到。
- ⚠️ **实际周期是 30s 而非"整分"**：`< 60s` 保证每个桶在被逐出前至少翻转一次（`METRICS_FLIP_INTERVAL_MS=30000`）。
- ✅ **额外（spec 新增）**：整点顺带把上一小时的分钟行 rollup 进 `trace_metrics_hour`，并在同一周期做保留期清理。

### 5.3 分位数实现的分层

- ~~**v1（推荐）**：原始样本 + 翻转时排序。零依赖、精确、代码十几行，满足约束 #8（极简优先）。~~ ✅ 已落地（最近秩）
- **v2（演进）**：QPS 实测撑不住时换 Glowroot 式**对数分桶直方图**（常量内存 + 估算）。**表结构不动，只换 Accumulator 内部实现。** → ❌ 仍未做（spec out of scope，只留 seam）

## 6. 与存储层的关系

- ~~聚合器是**内存累加器**，落库走存储层。~~ ✅
- ~~位置：`storage/` 子包，命名如 `TraceMetricsAggregator`（聚合计算）；建表 SQL + upsert SQL + TTL DELETE 进 `H2SqlStatements`。~~ ⚠️ **位置改了**：实际独立子包 `...reporter.logfile.metrics/`（与 `storage/`、`alert/` 三分），SQL 常量仍在 `H2SqlStatements`。
- ~~落库批量 = "每整分一次、每批 = 冻结桶数"，量级很小（几十~几百行/分）→ 直接复用 H2 现有写线程 + `synchronized` 片段（`storeLog` 同款）。**不引第二条写线程、不引第二套有界队列**——别为优雅引入第二套异步骨架。~~ ✅（`storeMetricRows` 直调，复用存储锁；只新增一个 30s 守护定时器，无队列）
- ~~错误处理同款：catch + `recordError` + 限速日志，绝不外抛。"监控只是助力"。~~ ✅
- ~~TTL 30d：`DELETE FROM trace_metrics_minute WHERE time_bucket < ?` 挂进现有 TTL 定时器顺带跑（§5.4）。~~ ⚠️ **未挂独立定时器**：由 30s 翻转周期顺带执行；保留期改为**常量桶数**（分钟 2880=48h、小时 720=30d），**无 `retention_days` 配置项**。

## 7. 对外 Facade：SWMetricsUtils

~~完全复刻 `SWLogfileReporterUtils` 范式：~~ ✅

| 方法 | 职责 | 数据来源 | 状态 |
| --- | --- | --- | --- |
| `Map statisticMetrics()` | 进程内**当前分钟**累计快照（实时感） | 反射取聚合器内存桶 → `Map`（JDK 原生） | ✅ |
| `Map queryMetrics(Map condition)` | 历史分钟桶查询（service/endpoint/时间范围） | 反射取存储层 → 读 H2 聚合表 → `List<Map>` | ✅（+ `resolution` 分钟/小时路由、`aggregate=true`） |
| `Map extremeTraces()` | 每端点最大耗时那条 trace 的现场 | 聚合器内存 | ✅ spec 后加（指标→链路下钻） |

- ~~宿主桩放 business 侧 toolkit（与 `SWLogfileReporterUtils` 并列），运行期由新拦截器 `LogfileReporterMetricsExposeInterceptor` 接管。~~ ✅（类名实际为 **`MetricsExposeInterceptor`**，桩为 `SWMetricsUtils`，`.def` 已加 `sw-metrics-utils-9.x`）
- ~~跨 PluginClassLoader 走 `ServiceManager` + 反射取数——沿用 `LogfileReporterStatusExposeInterceptor` 每一步，特别是"必须用反射、别强转"的警告。~~ ✅
- ~~聚合计算全在 Agent 侧 Metrics Service，工具类只做 Facade（§7.1）。~~ ✅
- ~~防御：`queryMetrics` 结果集设上限（如 1000 行），超限截断 + 计数。~~ ✅（`LIMIT_DEFAULT=200` / `LIMIT_MAX=1000` / `MAX_QUERY_POINTS=2000` 三级）

## 8. 风险与已知取舍

| 取舍 | 决策 | 落地实况 |
| --- | --- | --- |
| 进程崩溃丢"未落库分钟桶" | **接受**，不引入 WAL | ⚠️ 内存模式下**更彻底**：进程重启丢全部指标（本期刻意取舍） |
| 迟到段跨桶重复计数 | **文档化为已知偏差** | ✅ 保留（改为超 3 桶窗口丢弃 + `lateDropped` 计数） |
| 小样本分位数的估计偏差（<10 样本） | 落库时**置空分位数列** | ⚠️ **阈值改为 20**（`MIN_SAMPLES_FOR_TAILS`）；且 `1≤n<20` **仍出 p50**，只置空 p90/p95/p99 |
| endpoint 维度桶数量爆炸 | 超出上限按 LRU 冻结 | ⚠️ **不是 LRU**：上限 500，超出**先到先得**并入 `(other)`，计 `endpointOverflow` |
| （spec 新增）指标判定 ≠ 告警判定 | — | ❌ 引入的**新偏差**：slow 不含 Ant 规则、error 不含 http 状态码/白名单 |
| （spec 新增）小时分位近似 | — | ❌ 新偏差：小时分位 = 分钟分位按 `request_count` 加权平均 |

## 9. 落地小步建议（~~已按此顺序全部收口~~）

1. ~~表 + SQL 常量（`trace_metrics_minute` DDL + `MERGE` + TTL DELETE）进 `H2SqlStatements`。~~ ✅
2. ~~`TraceMetricsAggregator`（内存桶 + 分钟翻转 + 分位数 + 落库编排），单测：多段 trace 计一次、迟到段覆盖、normal 只贡献 count/latency、翻转后桶清空、小样本分位数缺失。~~ ✅（单测按 a1 重写：子段不计、窗口内迟到/超窗丢弃、小样本 p50-only、endpoint 归并、全局 `"*"` 分位、整点 rollup）
3. ❌ 客户端加一行 `metricsAggregator.afterTraceMerged(traceId, merged)`（与告警并列），切流后随合并视图一起下移（§6.3）。→ **已取代**：`consume` 里 `metricsAggregator.onSegment(segment)`，天然在合并视图之下，无需"下移"。
4. ~~`SWMetricsUtils` 桩 + 拦截器，接进 `statisticStatus` 同款读口。~~ ✅（`/inner/sw/metrics` + `/query` + `/extremes`；大屏 `dashboards/metrics.html` 五档）
5. ~~TTL 定时任务加指标清理。~~ ✅（并入 30s 翻转周期；保留期为常量）

## 10. 一句话结论

❌ **已被取代**（原结论的三个前提中有两个被推翻）：

> ~~**复用 `TraceEvaluator.evaluate` 在合并视图事件上累加内存分钟桶，整分批量 upsert 进 `trace_metrics_minute`，Facade 走 SWMetricsUtils 反射范式。** 难点只有分位数和"trace 计一次"的口径，分别用样本排序 v1 和同桶 traceId 去重化解。~~

✅ **实际结论**：

> **在 `consume` 上按"入口段即一次事务"（a1）流式聚合，内存分钟桶 → 30s 翻转 → `MERGE` 进 H2 内存模式 `trace_metrics_minute` + 整点 rollup 进 `trace_metrics_hour`，Facade 走 `SWMetricsUtils` 反射范式。** 不复用 `TraceEvaluator`（告警包零改动），因此也不需要"trace 计一次"的去重——难点只剩分位数，用最近秩 v1 化解。

## 11. 待详细思考/拍板的点（~~已全部拍板，见细化稿 §12 台账~~）

| # | 待决 | 本讨论稿的推荐 | 落地实况 |
| --- | --- | --- | --- |
| a | 计数口径 | 同桶 traceId 去重，一次/分钟桶 | ❌ **推翻** → a1 入口段一次事务，不去重 |
| b | 耗时任取（Entry vs max span） | `maxDurationMs`（与告警同源） | ❌ **推翻** → 入口段自身耗时 |
| c | 分位数采样 | v1 全样本排序；v2 Glowroot 直方图 | ✅ v1 落地（+ 蓄水池）；v2 未做 |
| d | normal 计入方式 | 自动计入 request_count + latency 分布 | ✅ 落地 |
| e | SWMetricsUtils 形态 | `statisticMetrics()` / `queryMetrics(Map)` | ✅ 落地（+ `extremeTraces()`） |
| f | 落库线程 | 复用现有写线程 + synchronized，整分批量 | ✅ 落地（30s 守护定时器，无队列） |
| g | TTL | 30d，`time_bucket < now-30d` | ⚠️ **改** → 常量桶数 2880/720，翻转顺带删，无配置项 |

---

## 12. 剩余部分（2026-09-25 记录，本轮不动）

- **建议优先**：① H2 file 模式（当前仅内存模式，重启丢全部指标，`7d/30d` 失去意义）；② 指标与告警判定口径统一（当前两套判定，大屏与告警可能互相打脸）；③ 分位数 v2 对数分桶直方图（消除采样误差 + 小时 rollup 加权平均近似）。
- **低优先**：endpoint 溢出改 top-N 归并；`retention_days` 配置化；`ExtremeTraceSelector` 阈值化；日桶第三层分辨率。
- **明确不做**：复用 `TraceEvaluator`、合并视图挂钩、traceId 去重、独立 TTL 定时器等（详见细化稿 §16.3）。

完整清单与取舍理由见 [`h2化-phase5-metrics实现细化.md`](h2化-phase5-metrics实现细化.md) §16。