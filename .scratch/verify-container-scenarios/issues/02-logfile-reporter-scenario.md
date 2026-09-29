# 02 — logfile-reporter 场景（A/B/C/D 断言移植）

**What to build:** `verify/scenarios/logfile-reporter/{scenario.conf,checks.sh}`：把 `validate.ps1` 的 A（trace 缓存合并）/ B（告警链端到端 + webhook 收讫）/ C（运行时开关）/ D（五类读口冒烟）断言移植为 bash+jq，覆盖等价。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] A/B/C/D 的断言项与 `validate.ps1` 覆盖等价（traceId 合并、Entry/Exit、span 字段齐全；慢/错/豁免/对照 + webhook 计数；开关前后增长；五类读口非空）。
- [ ] `bash verify/run.sh --scenario logfile-reporter` 在 Docker 内返回 0。
