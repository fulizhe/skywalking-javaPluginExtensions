# 07 demo-app 扩展（httpclient5 出口 + 矩阵属性 + 桩类）

Status: done
Type: task
Blocked by: 01

## 目标

让验证回路能真实产生 HttpClient 5.x 出口流量并读取开关状态。

## 范围

- `agent/demo-app/pom.xml`：
  - 新增 `<httpclient5.version>` 属性（默认落一个 5.x 版本）
  - 新增 `org.apache.httpcomponents.client5:httpclient5:${httpclient5.version}` 依赖
- 新增出口端点 `/api/trace-alert-demo/httpclient5-post`：
  - 用 httpclient5 向应用自身 POST 表单体（镜像 4.x 的 `httpclient-post` 回环），返回 `client=apache-httpclient5`
- 新增 `org.apache.skywalking.apm.toolkit.SWHttpClient5CollectUtils` 宿主桩类（方法同插件侧，桩返回 FALSE/空 map）
- 新增 httpclient5 collect 读口控制器（`status` / `statistic` / `toggle`，镜像 `SWHttpClientCollectController`，内部调 `SWHttpClient5CollectUtils`），路径建议 `/httpclient5/collect/*`

## 验收

- demo-app 能启动，`/api/trace-alert-demo/httpclient5-post?value=...` 正常回环。
- 未挂插件时 `/httpclient5/collect/statistic` 正常返回（桩空态）。
- `<httpclient5.version>` 可被 `-Dhttpclient5.version=...` 覆盖（供矩阵注入）。
