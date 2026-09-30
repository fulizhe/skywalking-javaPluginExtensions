# 01 — 实跑取证：出口 span 的操作名格式与标签内容

**What to build:** 启动演示应用 + Agent，实跑造数后抓取**出口 span**的原始数据，确认真实世界的字段口径。本票**只取证、不写生产代码**——但它阻塞明细档的标签设计。

**Blocked by:** （无）

**Status:** resolved —— 结论见 `docs/notes/2026-09-30-exit-span-runtime-probe.md`

> **取证结果摘要**（三条都拿到了，附一条"未能取证"）：
> 1. `operationName` **已被 agent 归一化**（DB=`H2/JDBC/PreparedStatement/executeQuery`、HTTP=纯 path），**不含** query string / host / SQL 变量 / UUID → issue 12 原本担心的基数爆炸**不成立**，归一化规则**不需要写**，但上限保护保留。
> 2. `db.instance`（`dbtest`）与 `url`（`https://httpbin.org/status/400`）**都在 tagList 里** → 实例级依赖节点**零成本解锁**（不需要碰 `peer` 字段、不触发 ADR-03）。**本期按 spec 停在组件类型**，此项作为下一轮输入。
> 3. **`componentId=128`（hutool-http）不在组件库里** → 宿主侧**必须加 fallback**（已加：组件库 → `spanLayer(#id)` → `component-<id>`），且边为此多透出 `spanLayer` 字段。
> 4. ⚠️ **未能取证**：异步跨段形态 —— demo-app 造不出 `@Async` 出口依赖，实跑「有 Exit 但无 Entry」的段 **0 条**。故「异步不建边」只能靠 issue 02 的**负向单元测试**钉住，不可依赖实跑证据。

要回答三个问题（每个都要有原文/原样输出作为证据，不能凭 SkyWalking 常识推断）：

- [x] **出口 span 的操作名实际格式** —— 数据库出口是 `H2/JDBI/executeStatement` 还是 `JdbcTemplate/query`？HTTP 出口是全 URL 还是 `HttpClient/HttpClient4.x/Wrapper`？MyBatis 与 JdbcTemplate 两条路是否不同？
- [x] **出口 span 的标签里有没有 `db.instance`（或任何 `db.*`）** —— 当前**全仓无任何 `db.*` 实证**（唯一抓到的真实出口 span 是 gRPC，`tagList` 为空）。有则依赖节点可顺带考虑升到实例级；无则必须停在组件类型。
- [x] **回环自调用在段内/跨段各是什么形态** —— 被调侧新段的父段引用里带的对端地址，在**出口侧**能否对应上；确认段内配对在自调用场景下的实际产出。
  > **部分取证**：回环自调（Hutool → `127.0.0.1:9600`）实测**全部落在同一个段内**（Entry `post-json` + Exit `echo/json` 同段），
  > 故段内配对成立、对端地址即 `url` tag 里的 `http://127.0.0.1:9600/...`。
  > **但"跨段形态"一栏仍未取证** —— demo-app 造不出 `@Async` 出口依赖（实跑 0 段），
  > 故本条**不算完全完成**，保持部分勾选。跨段的取舍改由 issue 02 的负向单元测试钉住。

- [x] 用容器验证回路或本地调试脚本启动演示应用（挂 Agent），造数：数据库双路（MyBatis + JdbcTemplate）、HTTP 出口（回环自调 + 唯一的外呼验证端点）。
- [x] 从**现有读口**抓原始 span JSON（`/inner/sw/trace-query`），摘出出口 span 的 `operationName` / `componentId` / `tagList` 原样内容。
- [x] 结论落成一份开发笔记（`docs/notes/`），含：三个问题的答案、样例原文、以及**明细档右列标签该怎么写**的建议。

不做：

- [x] 不改任何生产代码、读口、配置。
- [x] 不为取证而新增出口 span 的字段捕获（对端地址留待后续票）。

## Comments

> 已知风险：演示应用的数据库是进程内嵌的、出口 HTTP 几乎全是回环自调，依赖面很薄。若某类调用在演示环境造不出来，**在笔记里明确写"未能取证"及原因**，不要用推断填补——后续票依赖这个结论的可靠性。
