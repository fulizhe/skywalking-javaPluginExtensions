# 03 — override-httpclient 场景 + httpclient 版本矩阵

**What to build:** demo-app 增加 httpclient POST 表单体端点（`/api/trace-alert-demo/httpclient-post` + `/api/trace-alert-demo/echo`）与 `<httpclient.version>` 属性；`verify/scenarios/override-httpclient/{scenario.conf,checks.sh,support-version.list}`：开关读口 + enable/disable + 端到端 `http.request.params` tag 断言；对 `4.5.13`/`4.5.14` 跑矩阵。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 单版本 `bash verify/run.sh --scenario override-httpclient` 返回 0。
- [ ] `bash verify/run.sh --scenario override-httpclient --matrix` 两个版本均返回 0。
- [ ] 场景停用官方 `apm-httpClient-4.x-plugin`，避免重复增强。
