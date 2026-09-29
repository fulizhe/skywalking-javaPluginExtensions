# 03 — 端点表（按最差 P99 排序）+ 单端点下钻

**What to build:** 在排障页加端点表，数据取自 `aggregate=true` 每端点行；**默认按 `worstP99` 降序**；列含 `worstP50/P95/P99`、`bucketCount`，并保留加权平均作对照。点端点下钻：拉该端点逐桶序列（现有 `?endpoint=<ep>`），画逐桶 P99 曲线 + ③ 摘要（最差/中位/桶数/分布），并复用既有下钻入口（慢查询 / 双源对照 / 链路可视化）。

**Blocked by:** 01, 02.

**Status:** ready-for-agent

- [x] 端点表：列 = 端点 / `worstP50` / `worstP95` / `worstP99` / 加权平均 P95 / 加权平均 P99 / 请求数 / 错误率 / `bucketCount`。
- [x] 默认排序 `worstP99` 降序；支持切换按请求数排序。
- [x] 下钻：点端点 → 该端点逐桶序列 + 逐桶 P99 曲线（复用 `ensureSeries` 缓存/去重套路）。
- [x] 下钻 ③ 摘要：最差分钟 / 中位分钟 / 桶数 / 每桶 P99 分布（前端计算）。
- [x] 下钻沿用既有入口链接：慢查询（`trace-slow.html`）/ 双源对照（`trace-query.html`）/ 链路可视化（`trace-view.html`）。
- [x] 分辨率 > 24h 时文案由"最差分钟"改为"最差小时"。
