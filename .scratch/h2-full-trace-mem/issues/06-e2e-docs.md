# 06 — 端到端收口 + 文档回写

**What to build:** 把 01–05 的成果在验证回路上收口：扩 `validate-h2.ps1` 断言覆盖"全量含 normal / 双源一致 / 慢查询命中 / QPS 分母正确 / metrics 差异化慢"，`validate.ps1` 保持对外零行为变化；回写路线与状态文档。

**Blocked by:** 01, 02, 03, 04, 05 — 端到端收口依赖全部功能票完成。

**Status:** ready-for-agent

- [ ] `validate-h2.ps1` 扩展断言全绿：H2 覆盖 normal、双源 `logs` 一致、慢查询返回命中段且可下钻、桶行 `qps` ≈ requestCount/桶跨度、配置 `slow_rules` 后 slow 计数反映差异化阈值
- [ ] `validate.ps1` exit 0（`statisticStatus` / 开关 / 告警行为 / 既有工具类契约零变化）
- [ ] 健康计数：`writeQueueDropped=0`、`h2ErrorCount=0`、`orphanSegments` 照常
- [ ] 文档回写：路线计划勾选对应 Phase 2 / Phase 4 项、README 配置表更新（`shadow_max_rows` 默认值等）、spec 状态置为完成
- [ ] 无新增 ADR（决策见既有 ADR-04）
