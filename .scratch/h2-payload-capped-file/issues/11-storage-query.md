# 11 — 存储侧查询：按 traceId 取回整条链路

**What to build:** 存储新增查询能力：按 traceId 取回整条链路（与旧 `data[traceId].logs` 同契约），以及最近 N 条 header 列表。三态：命中 / 不存在 / **payload 已过期**（header 在、payload 不在，明确标记）。

**Blocked by:** 08 — snapshot 读路径接入环形

**Status:** done

- [ ] `queryTrace(traceId)`：命中返回同契约视图；不存在返回空；过期返回 header + 过期标记
- [ ] `recentTraces(limit)`：返回最近 header（含 payload 是否过期）
- [ ] 异常就地捕获、计数，绝不外抛
- [ ] 单元测试覆盖三态与最近列表
