# Glowroot 链路存储与查询实现速览（一手核实）

> **用途**：本项目 H2 化 / 本地 trace 持久化设计的参考底稿——**"内存热层 + 本地持久化 + 内存优先查询"的同构范式**（与本插件当前形态最接近的外部实现）。
> **核实基准**：`glowroot/glowroot` @ `456b1910bbeeb152efd78103043d71c08b183975`（main，2026-09-08，`Prepare version 0.14.8-beta.6`），逐文件打开源码确认。
> **证据类型**：`【源码】`= 打开该 commit 源码确认；无【实测】——本仓库**未运行 Glowroot**（与 SkyWalking 实验环境不同）。
> **已核实**：采集阈值（只存慢/错）、写入路径与背压、H2 + `CappedDatabase` 存储模型、读取路径（active/pending → stored）、partial trace、过期与封顶。
> **未核实**（见 §7）：默认 Reaper 调度/过期时长、capped 文件默认大小、central（Cassandra）侧细节、UI 全部行为。
> **对照**：SkyWalking 侧见 `skywalking-oap-trace-storage.md`。

---

## 0. 一句话结论

1. **不是全量存 trace**：默认只有**慢**（`slowThresholdMillis` 默认 **2000ms**，可按 type/name/user 覆盖，0 = 全标慢）或**出错**（error）的 trace 才落库；普通请求只留聚合指标（维护者原话："Every request is not stored"）。
2. **内存里有"完成未落库"层，但不是周期批量压库**：专用单线程 **即时逐条**落库；pending 队列只是可见性缝隙 + 背压（`PENDING_LIMIT = 50`，队列容量 150）。
3. **存储 = H2 行 + 环形封顶文件**：H2 `trace` 表放 header/索引列 + capped_id 指针；entries/queries/shared query texts/profiles 写 `CappedDatabase`（`*-capped.db`，LZF 压缩 + 高频 fsync + **文件大小硬封顶**）。
4. **读取：active（运行中）→ pending（完成未落库）→ stored（H2 + capped）**；只有 `check-live-traces` 时才对 DB miss 重试（**5 次 × 500ms**）。
5. 长跑慢请求会**周期性存 partial 快照**（运行中即可查看）。

---

## 1. 采集阈值：选择性捕获

类：`agent/core/.../impl/TraceCollector.java`

- `shouldStoreSlow(transaction)`：已 partial 过 → true；否则依次看：事务级 override → transaction type / name / user 差异化阈值 → 全局 `TransactionConfig.slowThresholdMillis`（默认 **2000**；0 = 全部标记为慢）。
- `shouldStoreError(transaction)`：`errorMessage != null`。
- `collectTrace(transaction)`：`if (!slow && !shouldStoreError(transaction)) return;` —— **普通请求不落 trace**，只参与聚合。
- 高级 API：`Glowroot.overrideSlowTraceThreshold(...)` 可对特定事务调低/调高存储阈值（文档注明"记录特定 trace"用）。

---

## 2. 写入路径：pending 队列 + 专用单线程即时落库

```text
请求线程完成事务
  ▼
TransactionProcessor（后台）：transaction.setCaptureTime(...)
  │  注释原文：send to the trace collector *before* removing from transaction registry
  │  so that the trace collector can cover the gap (via getPendingTransactions())
  │  between removing the transaction from the registry and storing it
  ▼
TraceCollector.collectTrace(transaction)
  │   非慢非错 → return（不存）
  │   背压检查（正常完成 / 曾 partial 完成的待落库计数 ≥ PENDING_LIMIT=50 → 丢弃 + 限速告警）
  │   pendingTraces.offer(...)   ← LinkedBlockingQueue，容量 PENDING_LIMIT*3 = 150
  ▼
【专用单线程 Glowroot-Trace-Collector】TraceCollectorLoop
  │   take() → collectCompleted(transaction, slow) → TraceReader
  ▼
Collector.collectTrace(TraceReader)（embedded：EmbeddedCollector → TraceDao.store）
```

要点：

- **不是"攒着批量压库"**：单线程 take 一条落一条；pending 只覆盖"完成 → 落库"之间的可见性缝隙与背压，**不按时长保留**。
- 背压实现细节（@ `456b191`）：计数 `normalCompletePendingCount` / `partialCompletePendingCount` 虽被检查，但本 commit 里**从未自增/自减**（疑似未完成的残留）；实际兜底是**队列容量 150 满时 `offer` 失败 → 丢弃 + 告警**。
- partial：`ImmediateTraceStoreWatcher` 每秒巡检，对即将达到 `immediatePartialStoreThresholdSeconds` 的慢事务**周期性存 partial 快照**（`storePartialTrace` → `transaction.setPartiallyStored()`）；`0` 表示禁用该机制。
- **未核实**：`immediatePartialStoreThresholdSeconds` 的默认值（见 §7）。

---

## 3. 存储模型：H2 行 + CappedDatabase 载荷

类：`agent/embedded/.../repo/TraceDao.java`、`agent/embedded/.../util/CappedDatabase.java`

H2 `trace` 表列（`store()` 里 `merge into trace (...) key (id)`）：

| 列 | 说明 |
| --- | --- |
| `id` / `partial` / `slow` / `error` | traceId + 标记 |
| `start_time` / `capture_time` / `duration_nanos` | 时间与耗时 |
| `transaction_type` / `transaction_name` / `headline` / `user` / `error_message` | 索引 / 展示列 |
| `header` | `VARBINARY`，`Trace.Header` protobuf |
| `entries_capped_id` / `queries_capped_id` / `shared_query_texts_capped_id` / `main_thread_profile_capped_id` / `aux_thread_profile_capped_id` | **指向 CappedDatabase 的指针** |

索引：`trace_idx(id)`、`trace_capture_time_idx(capture_time)`（Reaper 用，注释："very important when trace table is huge"）、慢/错查询组合索引（`trace_overall_slow_idx`、`trace_transaction_slow_idx`、`trace_error_idx`、`trace_transaction_error_idx`，命中 `readSlowPoints` / `readErrorPoints` / `readErrorCount`）。

`CappedDatabase`（`*-capped.db`）：

- 环形**封顶**文件（块头 + 大块写出），LZF 压缩，**aggressive fsync**（源码注释：*"to minimize chance of invalid trace records on abrupt JVM stop"*）。
- 重载荷（entries / queries / shared query texts / profiles）不进 H2，按 id 读写；**超出封顶大小即被覆盖淘汰**。
- 这样 H2 只承担"索引 + header"，避免大 CLOB 撑爆 `.mv.db`（对比 SkyWalking 全塞 `data_binary`）。参考 issue #755：曾有用户 `data.h2.db` 涨到 31G。

> 文件格式与算法细节（文件头、块结构、覆盖判定、fsync、resize、统计）见专文 `glowroot-capped-database.md`。

---

## 4. 读取路径：内存优先，DB 兜底

```text
UI 按 traceId 请求（带 check-live-traces）
  ▼
TraceCommonService（ui/...）
  │  1) live：LiveTraceRepositoryImpl
  │       active（transactionRegistry.getTransactions()，运行中）
  │     + pending（traceCollector.getPendingTransactions()，完成未落库）
  │     命中 → 直接返回内存视图（header / entries / queries / profile）
  │  2) stored：TraceRepository（H2 + capped）
  │     miss 且 check-live-traces → 最多重试 5 次，每次 sleep 500ms
  │     （注释：trace may be completed, but still in transit ...）
  ▼
返回 header / entries / queries / profile
```

- 列表类查询：H2 走 `trace` 的慢/错组合索引（`readSlowPoints` / `readErrorPoints` / `readErrorMessages`）；同时 `LiveTraceRepository` 的 active/pending 点会并入列表（`getMatchingActiveTracePoints` / `getMatchingPendingPoints`）。
- `trace_attribute` 表：attribute 维度查询（`trace_id` 索引）。
- `LiveTraceRepositoryImpl` 的类注释明确了三层顺序：*"checks active traces first, then pending traces (and finally caller should check stored traces) to make sure that the trace is not missed if it is in transition between these states"*。

---

## 5. 过期 / 保留

- H2 `trace` 行：`TraceDao.deleteBefore(captureTime)` 删 `trace` + `trace_attribute`（embedded 的 `ReaperRunnable` 定时调用；`capture_time` 有专门索引）。
- capped 文件：按**文件大小硬封顶**（不是按 TTL 逐条删）。
- 配置入口：Administration → Storage（H2 retention + `*.capped.db` sizes）。【官方维护者在 issue #1060 中的说明，非源码逐行核实】
- **未核实**：默认过期时长 / capped 文件默认大小（见 §7）。

---

## 6. 与本项目 / SkyWalking 的对照

| 维度 | Glowroot（embedded） | SkyWalking OAP | 本项目启示 |
| --- | --- | --- | --- |
| 采集 | **只存慢（默认 2s）/错** | 全量 segment 上报（可采样） | 排查工作流以 error/slow 为主 → 可选择性持久化 |
| 写入 | 完成即（专用线程）逐条落库；pending 兜可见性 | Agent 队列仅传输 + OAP 批量落库 | 写穿：内存同步 + H2 异步批量 |
| 内存层 | **可查询**：active + pending | 不可查询 | 保留 `MemoryTraceSegmentStorage` 做热层 |
| 载荷 | H2 行 + capped 文件 | 整段进 `data_binary`（H2=`MEDIUMTEXT`） | 全塞 H2 需盯 `.mv.db` 膨胀（见 issue #755） |
| 查询 | 内存优先 → DB 兜底（+ 500ms×5 重试） | 直接查存储 | 内存优先、H2 兜底 |
| 保留 | Reaper + capped 文件封顶 | 全局 TTL（`time_bucket`） | 分级 TTL + 容量上限 |

---

## 7. 未核实 / 边界

1. embedded 默认数据保留天数 / capped 文件默认大小（配置页默认值未逐处确认）。
2. `ImmediateTraceStoreWatcher` 对应的 `immediatePartialStoreThresholdSeconds` 默认值。
3. central（Cassandra）侧：仅知 gRPC `collectTrace` → Cassandra、retention 以 per-insert `USING TTL` 写入（来自 issue #1060 维护者说明，未逐行核实源码）。
4. UI（`ui/`）traces 列表页对 live/stored 的合并展示细节未全部展开。
5. **全部结论来自源码，未在本环境运行 Glowroot 实测**（与 SkyWalking 的【实测】不同）。

---

## 8. 源码锚点（@ `456b191`）

| 主题 | 文件 |
| --- | --- |
| 采集阈值 / pending / 落库线程 | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/core/src/main/java/org/glowroot/agent/impl/TraceCollector.java> |
| 完成后处理（可见性缝隙） | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/core/src/main/java/org/glowroot/agent/impl/TransactionProcessor.java> |
| 内存查询（active + pending） | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/core/src/main/java/org/glowroot/agent/live/LiveTraceRepositoryImpl.java> |
| UI 查询路径（live → stored + 重试） | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/ui/src/main/java/org/glowroot/ui/TraceCommonService.java> |
| H2 `trace` 表 + capped 指针 | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/repo/TraceDao.java> |
| capped 环状文件 | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/util/CappedDatabase.java> |
| partial 快照 | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/core/src/main/java/org/glowroot/agent/init/ImmediateTraceStoreWatcher.java> |
| embedded collector（落库入口） | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/init/EmbeddedCollector.java> |
| 默认慢阈值（2000ms） | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/common/src/main/java/org/glowroot/common/config/TransactionConfig.java> |
| central gRPC 上报 | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/core/src/main/java/org/glowroot/agent/central/CentralCollector.java> |
