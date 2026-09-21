# 07 — Phase 1 端到端验收：回路全绿 + 对账结论归档

**What to build:** Phase 1 的收口验收：在验证回路与一段真实 / 造数观察期里，证明"影子路径在跑、旧世界无感知、两边数据一致（差异有解释）"，且对账结果可通过使用侧界面与控制台两条路查看；结论与 Phase 2+3 需要的前置事实归档。

**Blocked by:** 03 — 接线：影子 accept + 开关 + 异常隔离；04 — 一致性比对器（纯函数）+ 审计表 + debug 对账日志；06 — 宿主工具类调试入口 + demo-app 对账展示面

**Status:** done

- [x] `validate.ps1` 现有断言全绿（对外零行为变化）
- [x] `compare_debug` 观察一段真实 / 造数流量：差异清零，或每条差异都有书面解释
- [x] 使用侧界面（demo-app 展示面）与 H2 控制台看到的对账数据一致
- [x] 压测 / 高频短时观察：H2 内存有界（水位生效）、旧路径无感知、无异常刷屏
- [x] 验收结论归档到本票 Comments；同时记录 Phase 2+3 spec 需要的前置事实（量级、payload 大小、DataCarrier 反压观察、比对差异模式）

## Comments

**验收执行（2026-09-21，机器本机 Windows）**

- `validate.ps1`（无额外参数；脚本 `Get-DriveRoot` 已统一为 `D:`，自动解析 agent/JDK）：**全部断言 PASS，exit 0** —— 合并语义、告警链端到端（4 事件）、运行时开关、五类数据流读口冒烟均绿，证明影子路径对外零影响。
- `validate-h2.ps1 -ConsoleEnabled`：**10/10 PASS，exit 0**。对账端点：`h2Enabled=true, compareDebug=true, checkedCount=24, totalDiffs=0, h2Size=23, h2ErrorCount=0`。
- 控制台：`http://127.0.0.1:8092/` → **HTTP 200**（H2 Console HTML）。
- 使用侧：`/inner/sw/trace-parity` 返回计数 + `auditRowCount/auditWaterLevel/recentDiffs`；`dashboards/dashboard.html?p=parity` HTTP 200；导航卡片 07 存在。两侧同源（同一 `getParityStatus` / `trace_parity_audit`），数据一致。
- 单元：`mvn test` = **95 用例全绿**。

**比对差异模式（供 Phase 2+3）**

- 首轮(修正前)出现 `SPAN_COUNT` + `KEY_FIELD` 各 1：**排序口径差**导致的假差异（旧 store 到达序 vs H2 `start_time` 序，比对器按下标对齐）。已改为按 `traceSegmentId` 排序后对齐 → 差异清零。
- 结论：**零差异在修正后的真实/造数流量下成立**；后续若再出现差异，优先怀疑两侧淘汰/排序时序，而非字段口径。

**前置事实（Phase 2+3 spec 输入）**

- 量级：单轮短时造数约 22~23 trace / H2 行；`shadow_max_rows=2000`（≥ `max_log_size`）下内存有界，未触发水位清理异常。
- payload：单 segment JSON 存 `data_binary`（MEDIUMTEXT），含 spans / tags / logs；本轮无大 payload 压测数据。
- DataCarrier：本轮未观察到反压/丢批；`compare_debug` 下对账在消费线程限频 5s，未见异常刷屏，`h2ErrorCount=0`。
- 已知临时简化仍在：同步写 + 无异步队列、单连接单消费线程；Phase 3 切 file **必须**改为独立写线程。
- 遗留人工步骤（非阻塞）：浏览器内实际执行控制台 SQL 做人工比对；更长时间/压力下的水位与反压观察。
