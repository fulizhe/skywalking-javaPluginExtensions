# 03 — override-httpclient 场景 + httpclient 版本矩阵

**What to build:** demo-app 增加 httpclient POST 表单体端点（`/api/trace-alert-demo/httpclient-post` + `/api/trace-alert-demo/echo`）与 `<httpclient.version>` 属性；`verify/scenarios/override-httpclient/{scenario.conf,checks.sh,support-version.list}`：开关读口 + enable/disable + 端到端 `http.request.params` tag 断言；对 `4.5.13`/`4.5.14` 跑矩阵。

**Blocked by:** 01

**Status:** done

- [x] 单版本 `bash verify/run.sh --scenario override-httpclient` 返回 0。
- [x] `bash verify/run.sh --scenario override-httpclient --matrix` 两个版本均返回 0。
- [x] 场景停用官方 `apm-httpClient-4.x-plugin`，避免重复增强。

## Comments

**实现（2026-09-29，commit b544ffc / f30c4db / 1581339）**

- demo-app 侧：`pom.xml` 新增 `<httpclient.version>4.5.14</httpclient.version>`（覆盖 Spring Boot 管理的 Apache HttpClient 版本，即矩阵可变点）；`TraceAlertVerifyController` 新增 `GET /api/trace-alert-demo/httpclient-post`（默认表单值 `verify-body`）与 `POST /api/trace-alert-demo/echo`（`:125` / `:153`），构成"发起 POST → 目标端 echo"的表单体闭环。
- 场景侧：`scenario.conf` + `checks.sh` + `support-version.list`（`4.5.13` / `4.5.14`）。`scenario.conf` 设 `REMOVE_OFFICIAL_HTTPCLIENT` 停用官方 `apm-httpClient-4.x-plugin`，由 override 插件自建 Exit span，避免重复增强。
- 断言 5 项：采集开关读口 → `toggle=false` → `toggle=true` 三段，加端到端 `http.request.params` tag 断言（`checks.sh:13/17/21/32-33`，断言 Exit span 的 `tagList[]` 含 `tag-key=http.request.params` 且值含 `verify-body`）。
- 容器实跑（2026-09-29 晚）：单场景 PASS=5 (exit 0)；`--matrix` 下 `4.5.13` 与 `4.5.14` 均 PASS=5 (exit 0)，即两版本都可被增强与采集。默认全场景跑亦全绿。
- 实跑暴露并修复两处：① 开关断言取错字段——demo 控制器 `extractEnabled` 找 `effectiveCollectHttpParams`，这是插件**从不返回**的键，故 `enabled` 恒 `false`、断言必失败；已改断言插件真实字段 `.raw.overrideCollectHttpParams`（改读 `/httpclient/collect/statistic`）。② 插件 jar 名与加载正则按模块名写错——真实产物是 `override-apm-httpclient-4.x-plugin-9.4.0.jar`（带 `override-apm-` 前缀），已按真实产物名修正 glob 与 `PLUGIN_LOAD_REGEX`。
- 遗留（不属本工单，另开 task）：demo 控制器 `extractEnabled` 找 `effectiveCollectHttpParams` 与插件实际键不符，属既有读口瑕疵；本次只让场景断言真实信号，未改 demo 行为。
