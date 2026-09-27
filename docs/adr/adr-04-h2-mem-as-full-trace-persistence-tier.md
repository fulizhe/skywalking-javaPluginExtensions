# H2 内存模式作为全量 trace 持久层（含 normal），KeyedLocalStore 降为参照

> **状态**：accepted（2026-09-27 定案）。按 `docs/adr/index.md` 约定 ADR 通常在落地后归档；本决策**先于实现**记录——它**取代**了原 Phase 2「收窄」路线，需尽早留痕，故提前归档。

## 背景

- 原方案（`docs/todos/h2化-统一方案.md` §1.4）定：H2 **只存 metrics + slow/error 明细，normal 不落库**；normal 仅进内存热层 `KeyedLocalStore`（`MAX_LOG_SIZE=1000`）——**用户只能查到最近 1000 条 trace**。
- 用户诉求升级：**尽量多存 trace 以支持任意链路追踪查询**，且不显著增负担；从"仅前 1000 条"获得明显进步。
- 容量事实（`docs/notes/2026-09-27-h2-mem-capacity-estimate.md`）：H2 mem 只存 header 行时 **~0.7 KB/行**；payload 在堆外环形文件（`ADR-03`）。`N=10 万` 行仅 **~70 MB 堆**，远小于"用满 128 MiB 文件"所需的 ~200 MB。

## 决策

1. **H2 内存模式的 `trace_segment` 作为全量 trace 持久层**：normal + error/slow **都写**（**不启用收窄**）；payload 全量 GZIP 落 `CappedFileStorage`。
2. **查询以 H2 mem 为准**；`KeyedLocalStore` 降为**兼容保留 + 过渡期交叉核对参照**（主从反转：此前 H2 是内存的影子，现在 H2 是真相、内存做比对）。
3. **单表 `trace_segment`**（**不**单独建 error/slow 表）；**同时删除 `trace_level` 列**：错误用 `is_error` 查，慢用 **`endpoint` + `latency >= :阈值`** 查（查询参数化，无需写时打标签）。
   - `latency` 为**段级**（段内 `maxEnd-minStart`）；多段链路的"慢"按 traceId 取 `max(latency)` 聚合。
   - 阈值由调用方**按 endpoint 传入**（不同端点可不同，解决"正常耗时 10s"端点的误判）。
   - 提供**专门的慢查询页面**（选 endpoint + 阈值 → 点开看慢链路）。
4. **参数**：`shadow_max_rows = 100,000`；`payload_capped_size_mb = 128`（`F ≥ N×S`，确保容纳 10 万段）；`persist_only_hits` 保留、默认 `false`。
5. **读口**：`getTraceView(traceId)` 保持 H2 为准；**新增** `getTraceViewFromMemory(traceId)` 读 `KeyedLocalStore`（宿主桩 + 拦截器各加一方法）；demo trace 查询页加**双源对照**（H2 为准 vs 内存参照 + 一致性/差异高亮）。
6. **file 模式延后**：仅当需要"跨进程重启持久化"时再做；本期维持 mem。
7. **slow 判定路径**：告警走 `TraceEvaluator`（合并视图 + `slow_rules`）；**metrics 复用同一套 `slow_rules`**——经**只读阈值解析器**（`RulesEngine.matchSlow` 纯函数，不触发 `recordRuleHit`、不并入 `TraceEvaluator`），使"正常耗时 10s"的端点可配差异化阈值。二者都**不依赖** H2 `trace_level`。（列为 Phase 5 增强。）
8. **孤段过滤保持**：`isOrphanSegment`（无 ref 且无 Entry）判定的段仍**被过滤**，不入 H2；计数照旧暴露在 `/inner/sw/trace-parity`。
9. `KeyedLocalStore` 的 `MAX_LOG_SIZE` 维持 **1000**（作近期参照；其行为调整另行确认）。

## Considered Options

- **收窄（只存 error/slow，原 Phase 2）**：拒绝（本期）。normal 不进 H2 使"任意链路查询"残缺；而 header-only 成本证明全量入 H2 可负担。
- **单独 error/slow 表**：拒绝。normal 已全量入表，"normal 污染"不再成立；拆表带来双写、双 cap、对账、指针归属成本；分档 TTL 在 mem 下无意义，留待 file 阶段评估。
- **退役 `KeyedLocalStore`（真统一，方案 A）**：本期不做。会牵动 `ADR-02`（存储形态）、读门面（`getLogfileStatMap()`）、告警触发点（`mergeLogIntoStatMap` → `afterTraceMerged`）；改动大而收益相对小。降为参照即可。
- **环形文件继续设为 16–32 MB**：拒绝。`F ≥ N×S` 才能让 header 覆盖 payload；容纳 10 万段需余量 → 取 128 MB。

## Consequences

- H2 mem 的**堆随行数线性涨**（~0.7 KB/行）；`N=10 万` ≈ 70 MB（再叠加 H2 页/B 树开销）。
- **窗口 = 分钟~小时级**（@10 QPS ≈ 2.8 h、@100 QPS ≈ 17 min），且**重启即失**；跨重启持久化需 file（后续阶段）。
- **`trace_level` 列删除**：错误靠 `is_error`、慢靠 `latency` 阈值查询；由此**规避"写入早于合并"的 trace 级判定时点问题**（写入无需知道 level）。`persist_only_hits` 不再起过滤作用（保留供回退/对比）。
- **slow 无持久标签**：告警/metrics 各自按规则判定（见决策 7）；H2 明细如需慢查询，用 `latency >= :阈值`（动态参数，比固定标签更灵活）。
- `KeyedLocalStore` 与 H2 **双写同份数据**（前者仅 1000 条，作近期参照）；`data[traceId].logs` 读契约、告警/webhook 路径**零改动**。
- 影子对账方向反转：由"H2 对内存"改为"内存对 H2"（H2 为准）。
- 原"Phase 2 收窄"路线作废，改写为"**全量 trace 入 H2 + 过渡期双源核对**"。

## 参考

- 容量估算与实测校准步骤：`docs/notes/2026-09-27-h2-mem-capacity-estimate.md`
- 载荷拆法（header + 环形封顶文件）：`docs/adr/adr-03-capped-file-payload-for-trace-details.md`
- 两种存储形态（不强行统一）：`docs/adr/adr-02-two-shapes-for-bounded-local-storage.md`
- 路线与阶段：`docs/todos/h2化-统一方案.md`
