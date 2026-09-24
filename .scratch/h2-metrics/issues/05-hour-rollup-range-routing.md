# 05 — 小时 rollup + 范围路由 + 7d / 30d

**What to build:** 整点触发把分钟行 rollup 成小时行（同结构、含 `"*"`），查询按跨度自动选分辨率；`7d` 与 `30d` 档位可出图且点数受控（**30d 本期一并实现，不再预留**）。

**Blocked by:** 04.

**Status:** ready-for-agent

- [x] rollup：request / error / slow / total_latency 求和、max_latency 取最大（精确）；p50 / p90 / p95 / p99 按 request_count 加权平均（近似，文档化为已知偏差）。
- [x] 只对**完整结束**的小时做 rollup；重跑幂等。
- [x] 查询缺省按 `[fromBucket, toBucket]` 跨度自动选分辨率，返回点数不超过 `MAX_QUERY_POINTS`。
- [x] `7d` 与 `30d` 档位渲染正常（走小时分辨率），大屏前端不再置灰。
