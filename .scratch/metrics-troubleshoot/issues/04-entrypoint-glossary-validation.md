# 04 — 入口、术语与验证回路

**What to build:** 把排障页接进入口；补领域术语；扩验证脚本。`metrics.html` 保持零改动。

**Blocked by:** 02, 03.

**Status:** ready-for-agent

- [x] `dashboards/index.html` 增加排障页链接（标注"分位=范围内最差，口径不同于 metrics"）。
- [x] **不改 `metrics.html`**（如需在其中加一行链接，另立确认，不在本 ticket）。
- [x] `CONTEXT.md` 新增领域词「**最差分钟分位 (worst-minute percentile)**」，并说明与「加权平均分位」「池化真值」的区别。
- [x] 领域术语写入 `CONTEXT.md`（「最差分钟分位」，与「加权平均分位」「池化真值」并列）；页面入口登记在 `dashboards/index.html`。
- [x] `validate-h2.ps1`：断言排障页 HTTP 200；并保留 issue 01 的最差聚合断言。
- [x] 端到端人工核对：有流量时页面能出"当前 vs 最差"两条线，端点表按最差排序。
