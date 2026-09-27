# 01 — 全量 trace 持久化 + 放大容量 + 删 `trace_level`

**What to build:** H2 内存模式正式成为链路查询的唯一真相来源：normal 与 error/slow **全量写入**（无收窄开关），`shadow_max_rows` 提升到 **100000**，环形载荷文件维持 **128 MB**；`trace_segment` 移除 `trace_level` 列并在启动时干净重建 schema。造数后可从既有读口 `/inner/sw/trace-recent` 看到 normal 请求，`/inner/sw/trace-parity` 的 `h2Size` 随 normal 增长。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] normal 与 error 段都出现在持久层（`snapshot`/`recentTraces`），`size` 随 distinct trace 增长；`is_error` 正确
- [ ] `shadow_max_rows` 默认值为 100000；环形载荷文件按 128 MB 定长、写满覆盖不崩
- [ ] schema 不再有 `trace_level` 列，写入/读取均不依赖该列；内存模式下重建后无残留旧列（不污染同 JVM 测试）
- [ ] `queryTrace` / `recentTraces` / `snapshot` / `size` 既有契约不变；孤段（无 Entry 且无 ref）过滤照旧、计数仍在 `/inner/sw/trace-parity` 可见
- [ ] `validate.ps1`（对外零行为变化）与 `validate-h2.ps1` 全绿；`writeQueueDropped=0`、`h2ErrorCount=0`
