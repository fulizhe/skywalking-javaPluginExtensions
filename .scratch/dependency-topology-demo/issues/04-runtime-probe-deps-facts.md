# 04 — 实跑探针：三个新组件的出口 span 事实

**What to build:** 起真实环境打一轮，把 Redis / MySQL / Kafka 三条边的**实测事实**写进开发笔记：`componentId`、`componentName`（是否落在组件库、要不要 fallback）、`spanLayer`、操作名归一化情况、以及票 02 里每条**超时的实际生效值**。

**Blocked by:** 02（要有真实客户端调用）、03（要有中间件在场才能验绿边）。

**Status:** open

## 为什么必须做

上一轮（`0578cb4`）探针**推翻了两处预设**：operationName 已被 agent 归一化、实例身份由 `db.instance`/`url` 标签零成本提供。
本轮同样有三处不能当预设：

| 待验 | 为什么要验 |
| --- | --- |
| `kafka-clients` 版本是否落在 `apm-kafka-plugin` 支持范围 | 超出范围会**静默不生效** → 图上没有 Kafka 节点 |
| 三个组件的 `componentId` 是否都在组件库里 | 不在 → 页面显示 `xxx(#id)` fallback（与 hutool 的 128 同款），README 要照实写 |
| Kafka / Redis 的操作名归一化成什么 | 决定明细档节点标签与"加操作名能加几个节点"的预期 |

## 要落的结论

- [ ] 写一份 `docs/notes/YYYY-MM-DD-deps-demo-topology-probe.md`，含：
  - [ ] 三条边逐条的：端点 → `componentId` → `componentName` → `spanLayer` → `operations[]`
  - [ ] Redis 三种 op / Kafka produce+consume 在**明细档**分别落成几个节点（验证 US「加操作名」的预期）
  - [ ] **每条超时的实际生效值**（含"Boot 属性名是否被认"，不认则走了哪个客户端配置）
  - [ ] 未起中间件时红边的形态：边是否存在、`errorCount`/`sampleCount` 是否照常增长、`p90` 是否仍是 `-1`（样本不足 20）
  - [ ] **推翻或确认**的预设逐条列出（沿用 9-30 两篇的写法）
- [ ] 探针结论若与 spec / README 的措辞冲突，**以实测为准**并回头改文档（票 06）。

## 不做

- [ ] 不为了"让图好看"去改插件行为或加映射兜底。
- [ ] 不做实例级 / peer 维度验证（承接既有 spec 的 Out of Scope；解锁条件已记录）。

## 验证

- [ ] 笔记里的每个数字都能由一次具体请求复现（记下命令）。
- [ ] 页面总览档截图与笔记结论一致（组件数、节点名）。