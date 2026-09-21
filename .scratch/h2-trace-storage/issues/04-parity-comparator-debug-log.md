# 04 — 一致性比对器（纯函数）+ debug 对账日志

**What to build:** 开启 `compare_debug` 后，影子数据自动与旧内存视图对账：以旧 store 当前数据为基准，逐 traceId 比对 logs 条数 / span 数 / 关键字段，差异只进日志（计数 + 限速明细），让人尽早发现新路径口径问题。

**Blocked by:** 03 — 接线：影子 accept + 开关 + 异常隔离

**Status:** done

- [x] 比对器为纯函数：输入旧快照、新快照、待比 traceIds，输出差异报告（缺失 / logs 数 / span 数 / 关键字段 / 解析失败各类）
- [x] 触发机制：`consume` 批次末尾（旧路径与影子 `accept` 都完成后）、仅 `compare_debug=true`；只比**本批涉及的 traceIds**（迟到 segment 由其所属后续批次再次覆盖，自愈）
- [x] 限频：默认 ≥5s 一轮，期间累积 traceIds，到点一次取两份快照比对（封顶消费线程上的 debug 成本）
- [x] 输出：累计计数（checked / 各类差异数）+ 限速明细（同类差异 30s 最多一条，带样例 traceId）；比对自身异常吞掉
- [x] 单元测试覆盖各差异类型与"零差异"路径
- [x] 票内备注：周期全量对账为后续备选（两边淘汰时序差会造假差异），本期不做

## Comments

- `TraceParityComparator`（纯函数，5 类差异 + 每类一条样例）、`runParityCheck` 限频 5s / 日志限速 30s、差异落 `trace_parity_audit`。
- **验收期修正（假差异）**：首轮实跑报出 `checked=2, totalDiffs=2 {SPAN_COUNT=1, KEY_FIELD=1}`。根因是两侧**排序口径不同**——旧 store 按到达顺序 append、H2 按 `start_time` 排序，而比对器按**下标**逐条对齐 → 同一 `i` 撞上不同 segment。修法：比对前两侧统一按 `traceSegmentId` 排序再逐位比对（`sortBySegmentId`），使对齐与到达/落库顺序无关。
- 新增回归测试 `zeroDiffs_whenLogsArriveInDifferentOrder`（集合相同、仅顺序不同 → 零差异）；`TraceParityComparatorTest` 11 用例全绿。
- 修正后实跑：`totalDiffs=0`（见票 07）。
- 证据时间：2026-09-21。
