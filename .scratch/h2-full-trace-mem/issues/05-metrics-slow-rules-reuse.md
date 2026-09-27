# 05 — metrics 慢判定复用 `slow_rules`（A 方案）

**What to build:** Trace 指标的慢判定不再只用默认阈值，而是复用告警同一套 `slow_rules`：引入一个**只读阈值解析器**（包装既有 `RulesEngine.matchSlow`），聚合器按入口 `endpoint`（及 url）取差异化阈值判慢。**不触发规则命中计数、不并入 `TraceEvaluator`、不改告警包行为**，消除"正常耗时 10s 端点"被默认阈值误判。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 新增只读阈值解析 seam（公共接口 + `alert` 包内的只读实现），实现仅调用既有纯函数 `matchSlow`，不调用 `recordRuleHit`
- [ ] `TraceMetricsAggregator` 注入该解析器（可为 null → 退化为仅默认阈值），据入口 `operation` + url（有则取）解析阈值后判 `slow`
- [ ] 命中规则端点：如 `/status/400=8000`，`duration=10000` 判 slow、`duration=5000` 判 normal（对照默认 3000 会误判为 slow）
- [ ] 告警行为零变化、规则命中计数不被双算；`slow_rules` 为空时与现状等价
- [ ] 配置不新增（复用 `Alert.SLOW_RULES` / `Alert.DEFAULT_SLOW_THRESHOLD_MS`）
