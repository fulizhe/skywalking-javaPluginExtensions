# 02 — TraceSegmentStorage 接口 + H2 内存模式实现

**What to build:** 新 trace 持久层模块的完整外部行为：`accept(List<TraceSegment>)` 把 segment 落入 H2 内存库（一条 segment 一行、`data_binary` 存该段 JSON），`snapshot()` 按 traceId 取行并按 `start_time` 排序、组装成与旧内存视图同构的 Map，`size()` 返回 trace 数；行数水位上限保证内存有界。全部异常就地捕获，绝不外抛。

**Blocked by:** 01 — 构建基座：H2 依赖打包 + 测试基建

**Status:** done

- [x] 启动时 `CREATE TABLE IF NOT EXISTS`，字段 / 索引按统一方案 §5.2（`trace_level` 本期可空）
- [x] `accept` 同步批量 insert（单连接）；任何异常计数 + 限速日志，不外抛
- [x] `snapshot()` 组装语义与旧视图对齐（`data[traceId].logs` 同构）；单元测试只经接口断言：traceId 合并、logs / span 计数、`start_time` 排序、`data_binary` JSON 往返
- [x] 水位上限：超过 `shadow_max_rows` 后按 `id` 水位节流清理；单元测试断言 `size` 有界
- [x] 配置字段就位（`plugin.logfilereporter.h2.*`）：`enabled`（默认 true）、`compare_debug`（默认 false）、`shadow_max_rows`（默认 2000）、`console_enabled`（默认 false）、`console_port`（默认 8092）

## Comments

- `TraceSegmentStorage`（`accept`/`snapshot`/`size` 三方法）与 `H2TraceSegmentStorage` 落地；JDBC URL `jdbc:h2:mem:sw_trace_segment;DB_CLOSE_DELAY=-1`；建表 + `trace_id` 索引 + `trace_parity_audit` 审计表。
- `H2TraceSegmentStorageTest` 9 用例全绿（合并 / 排序 / JSON 往返 / 水位 / disabled / span 计数）。
- 追加：为票 05/06 补齐审计表读口 `recentAuditRows(limit)` / `auditRowCount()` / `auditWaterLevel()`。
- 证据时间：2026-09-21。
