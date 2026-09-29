# 02 — 排障页骨架 + 全局 ③ 视图

**What to build:** 新增 `dashboards/metrics-troubleshoot.html`（沿用现有 `dashboards/` 结构与 echarts 风格），实现全局视图：把「当前·加权平均 P99」与「③·最差分钟 P99」并排，并给出中位分钟、有数据桶数、每桶 P99 的**小分布**。

**Blocked by:** 01.

**Status:** ready-for-agent

- [x] 页面骨架：五档范围（`1h / 6h / 24h / 7d / 30d`）+ 轮询刷新，与现有大屏一致的取数方式。
- [x] 全局序列取 `endpoint=*` 逐桶行（现有读口）；③ 的 `worst*`/`bucketCount` 取 `aggregate=true` 的 `"*"` 行（issue 01）。
- [x] 并排图：加权平均 vs 最差分钟（同一时间轴），标注两者差距。
- [x] 附：中位分钟、桶数、每桶 P99 分布（前端用已取到的逐桶序列计算，薄逻辑）。
- [x] 页内文案：注明"本页分位 = 范围内最差分位，与 `metrics.html` 的加权平均口径不同"，并提示"断流/小样本桶会放大最差值"。
- [x] 不改 `metrics.html`。
