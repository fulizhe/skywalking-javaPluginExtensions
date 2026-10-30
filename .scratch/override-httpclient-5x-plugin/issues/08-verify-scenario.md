# 08 verify 场景 override-httpclient5x

Status: done（文件已落地；端到端未在本机执行，需 `bash verify/run.sh --scenario override-httpclient5x --matrix`）
Type: task
Blocked by: 03, 07

## 目标

新建端到端验证场景，证明运行时开关 + httpclient5 表单体被采为 `http.request.params`，并对 5.x 版本矩阵跑增强。

## 范围

- 新目录 `verify/scenarios/override-httpclient5x/`：
  - `scenario.conf`：
    - `PLUGIN_MODULES="override-httpclient-5.x-plugin,logfile-reporter-plugin"`
    - `PLUGIN_JAR_GLOBS` 指向 5.x override jar + logfile-reporter jar
    - `PLUGIN_LOAD_REGEX` 匹配 `override-apm-httpclient-5.x-plugin-[0-9.]+.jar loaded`
    - `MATRIX_PROPERTY="httpclient5.version"`
    - `REMOVE_OFFICIAL_PLUGINS="apm-httpclient-5.x-plugin-*.jar"`
    - `AGENT_OPTS` 设 `plugin.httpclient.collect_http_params=true` 与 `plugin.overridehttpclient5.collect_http_params=true`
  - `support-version.list`：5.x 版本线（至少两个版本）
  - `checks.sh`：镜像 `override-httpclient/checks.sh`
    - 断言 A：`/httpclient5/collect/statistic` 的 `.raw.overrideCollectHttpParams == true`，toggle 关/开回读
    - 断言 B：打 `/api/trace-alert-demo/httpclient5-post?value=verify-body`，断言存在 Exit span 且 `http.request.params` 含 `verify-body`

## 验收

- `run-scenario.sh override-httpclient5x` 在矩阵各版本上通过。
- 场景运行后官方 5.x jar 已在 optional-plugins（未被双加载）。
