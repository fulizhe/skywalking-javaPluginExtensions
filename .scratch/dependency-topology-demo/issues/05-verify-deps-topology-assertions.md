# 05 — verify 断言：组件识别（必测）/ 绿边（有中间件时）

**What to build:** `verify/scenarios/logfile-reporter/checks.sh` 增加依赖拓扑的断言，**分两层让没有中间件的机器也能全绿**：组件识别与红边必测，绿边只在中间件在场时有意义。

**Blocked by:** 02（要有造数端点）；绿边部分另需 03。

**Status:** resolved —— 断言 F 按实测值写(kafka-producer 而非 Kafka);无中间件路径可过;容器内 bash 回路未跑(本机 docker daemon 不可用),bash -n 通过
## 断言分层

| # | 断言 | 前置 | 期望 |
| --- | --- | --- | --- |
| 1 | **造数端点不挂住** | 无 | 四个 `/api/deps-demo/*` 各自在 **< 5s** 内返回 200（粗断言，证明票 02 的超时生效） |
| 2 | **组件识别** | 无 | 拓扑读口出现 `Redis` / `MySQL` / `Kafka` 的 `componentName`，且**不是** `component-N` 兜底 |
| 3 | **红边（必测）** | 无 | 上述组件的边 `errorCount > 0` —— 中间件没起时 connect refused **照样出边**，这是"组件识别 + 错误标记"的完整证明 |
| 4 | **绿边（可选）** | 中间件在场 | 同组件边 `errorCount = 0` 且 `requestCount > 0` |
| 5 | **不回归** | 无 | 现有 Trace 指标与告警断言全绿 |

- [ ] 断言 4 用**可选跳过的写法**（先探测组件可达，再决定是否断绿边），不得写成恒真式。
- [ ] 断言 2 的组件名集合按**票 04 探针的实测结论**写（若某组件不在组件库，断言其 fallback 形态而非组件名）。

## 与既有断言的关系

- [ ] 复用既有助手（`assert_eq` / `assert_ge` / `assert_jq` / `http_get`），不新造断言框架。
- [ ] 分位口径沿用既有约定：**样本不足 20 时尾部分位合法为 `-1`，不比大小**。

## 不做

- [ ] 不给页面写断言（本仓验证回路只打读口，从不看页面 HTML）。
- [ ] 不为 self 节点（票 01 的前端改动）写自动化断言——无 JS 基建，靠实跑截图。
- [ ] 不让 verify 依赖 compose 中间件（verify 在宿主机直接跑 jar）。

## 验证

- [ ] `bash verify/run.sh --scenario logfile-reporter` 在**无中间件**的机器上全绿（断言 1~3、5）。
- [ ] 起中间件后再跑一次，断言 4 也通过。