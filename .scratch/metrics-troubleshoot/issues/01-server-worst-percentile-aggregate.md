# 01 — 服务端只读「最差分位」聚合

**What to build:** 扩展 `aggregate=true` 的读口响应：每个端点行（含全局保留键 `"*"`）**追加** `worstP50/worstP90/worstP95/worstP99`（范围内各桶对应分位的 `MAX`）与 `bucketCount`（有数据桶数，`COUNT(*)`）。纯 SQL、**不改表结构、不改写路径、不改 rollup**，字段只追加、向后兼容。

**Blocked by:** （无）

**Status:** ready-for-agent

- [x] `MAX(p50/p90/p95/p99)` 与桶数进入 SQL（分钟表 + 小时表两条路径，分辨率随范围路由）。
- [x] `aggregate=true` 的每端点行输出新增字段；全局 `"*"` 行同款。
- [x] 字段为**追加**，老字段与老调用方行为不变（契约向后兼容）。
- [x] 不改 `trace_metrics_minute/hour` 表结构、不改写入路径、不加 `median`。
- [x] 单元测试（in-memory H2，先例 `H2TraceSegmentStorageMetricsTest`）：断言 `MAX` 分组值与桶数正确。
- [x] e2e（`validate-h2.ps1`）：断言新字段存在、`worstP99 >=` 加权平均 `p99`、`worstP99 >=` 各桶 `p99` 最大值、`bucketCount > 0`（降采样场景用 `>=` 更稳）。
