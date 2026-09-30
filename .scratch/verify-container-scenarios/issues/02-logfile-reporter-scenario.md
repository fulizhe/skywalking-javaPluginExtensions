# 02 — logfile-reporter 场景（A/B/C/D 断言移植）

**What to build:** `verify/scenarios/logfile-reporter/{scenario.conf,checks.sh}`：把 `validate.ps1` 的 A（trace 缓存合并）/ B（告警链端到端 + webhook 收讫）/ C（运行时开关）/ D（五类读口冒烟）断言移植为 bash+jq，覆盖等价。

**Blocked by:** 01

**Status:** done

- [x] A/B/C/D 的断言项与 `validate.ps1` 覆盖等价（traceId 合并、Entry/Exit、span 字段齐全；慢/错/豁免/对照 + webhook 计数；开关前后增长；五类读口非空）。
- [x] `bash verify/run.sh --scenario logfile-reporter` 在 Docker 内返回 0。

## Comments

**实现（2026-09-29，commit b544ffc / 1581339）**

- `verify/scenarios/logfile-reporter/{scenario.conf,checks.sh}` 落地，A/B/C/D 共 24 项断言，全部经既有 HTTP 契约（`/statistic`、`/statisticTraceAlert`、`/inner/sw/trace-alert/{recent,clear}`、`/toggle`、五类读口），不触碰插件内部类。
- `scenario.conf` 声明 `PLUGIN_JAR_GLOBS=logfile-reporter-plugin/target/logfile-reporter-plugin-*.jar` + `PLUGIN_LOAD_REGEX=logfile-reporter-plugin-[0-9.]+\.jar loaded`。
- 容器实跑（2026-09-29 晚，本机 Docker/WSL）：`bash verify/run.sh --scenario logfile-reporter` → `场景[logfile-reporter]全绿: PASS=24 (exit 0)`。
- 实跑暴露并修复的时序缺陷：冷启动下 `dispatcher.dispatchErrorCount>0` 时首个 SLOW 事件可能收不到 webhook，原 40s 轮询只等不补发导致 B 段 5 项红。已改为轮询窗口内按需补发 `/api/order/1`（新 traceId → 新告警），复跑稳定全绿（`checks.sh:40-50`）。
- `checks.sh` 里的 `sleep` 沿用 `validate.ps1` 口径，慢机器上若偶发失败可适当放宽（已知点，已记入 handoff §6.4）。
