# 06 — 宿主工具类调试入口 + demo-app 对账展示面

**What to build:** 使用侧获得一个"调试按钮"：新增宿主工具类桩类（工作名 `SWTraceParityUtils`，`statisticParity()` 返回 JDK 原生 Map），由 agent 拦截器按既有范式接管，透出影子对账的计数与最近差异；demo-app 增加读口与页面区块展示，让人不开 SQL 控制台也能快速交叉验证。

**Blocked by:** 04 — 一致性比对器（纯函数）+ 审计表 + debug 对账日志

**Status:** done

- [x] 桩类按既有宿主工具类范式工作（类名增强 + 反射跨 ClassLoader 取数，`LogfileReporterStatusExposeInterceptor` 范式）；无 agent 时返回明确提示、不抛异常
- [x] `statisticParity()` 返回 JDK 原生 Map：checked / 各类差异计数 / 最近差异明细（来源 `trace_parity_audit`）/ 审计表水位状态
- [x] demo-app 增加读口与展示区块：能看到最近对账轮次、差异计数与差异明细
- [x] 宿主工具类既有契约不受影响（`SWLogfileReporterUtils` 现有方法与输出逐字段不变）

## Comments

- 桩类 `SWTraceParityUtils.statisticParity()` + `TraceParityUtilsInstrumentation` + `TraceParityStatusExposeInterceptor`；`skywalking-plugin.def` 增加 `trace-parity-utils-9.x`。
- 读口 `GET /inner/sw/trace-parity` → `StatisticController.traceParity()`。
- **补齐（本票验收时发现缺口）**：`getParityStatus()` 原先只返回计数，缺"最近差异明细 + 审计表水位"。已补 `auditRowCount` / `auditWaterLevel` / `recentDiffs`（来源 `trace_parity_audit`，`H2TraceSegmentStorage.recentAuditRows`）。
- **补齐展示面**：`dashboards/dashboard.js` 新增 `p=parity` 页（计数 chips + 最近差异表 + 空/未开比对提示），`dashboards/index.html` 新增编号 07 卡片 `H2 影子对账`。
- 实测：`/inner/sw/trace-parity` 返回 `{...,"auditRowCount":0,"auditWaterLevel":1000,"recentDiffs":[]}`；`dashboard.html?p=parity` HTTP 200；`dashboard.js` 含 parity 契约字段。
- 证据时间：2026-09-21。
