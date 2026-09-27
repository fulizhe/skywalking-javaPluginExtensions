# 04 — metrics QPS 一等字段 + 大屏

**What to build:** 在 Trace 指标对外契约中新增 `qps`（全局 `"*"` 行与每个 endpoint 行都有），分母按**该行的桶跨度秒**计算（分钟行 60、小时行 3600、范围聚合行用查询范围跨度），不写死 60；demo 指标大屏加 endpoint QPS 列与全局 QPS 卡片。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] `/inner/sw/metrics`（当前窗口分钟桶）与 `/inner/sw/metrics/query`（桶行 + `aggregate=true` 聚合行）返回的每行含 `qps`
- [ ] `qps` 分母正确：分钟行 = requestCount/60、小时行 = requestCount/3600、聚合行 = requestCount/(范围秒数)；不写死 60
- [ ] `metrics.html` 增加 endpoint QPS 列与全局 QPS 卡片，数据来自真实指标
- [ ] 既有指标字段与契约不变（纯新增 `qps`）
- [ ] 单元测试覆盖三种分母口径
