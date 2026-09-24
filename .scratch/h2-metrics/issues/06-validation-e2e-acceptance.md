# 06 — 验证回路与端到端验收

**What to build:** 把指标纳入既有验证回路，并跑通一次端到端验收：造流量 → 指标落库 → 大屏可读。

**Blocked by:** 05.

**Status:** ready-for-agent

- [x] `validate-h2.ps1` 增指标断言：指标行存在、含全局行、口径自洽（`error+slow ≤ request`、`p50 ≤ p95 ≤ p99`、`sample_count ≤ request_count`）、四档范围可查、查询上限截断。
- [x] `validate.ps1` 全绿（对外零行为变化）。
- [x] 端到端：造流量后 `/inner/sw/metrics` 读口与大屏显示一致。
- [x] `mvn -o -pl logfile-reporter-plugin test` 全绿。
