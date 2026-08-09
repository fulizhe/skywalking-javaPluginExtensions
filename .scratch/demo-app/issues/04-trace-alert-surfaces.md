# 04 — Trace 告警验证面移植

**What to build:** 移植 Trace 告警验证面:慢/错/忽略规则触发端点、webhook 接收器与进程内事件存储(recent/clear),随附 agent 告警配置样例——告警开 → 事件落存储,端到端可见。

**Blocked by:** 02 — 运行底座:agent 装配与启动脚本

**Status:** done

- [x] 慢/错/忽略规则触发端点可用(阈值与 Ant 规则可配置)
- [x] webhook 接收器收讫告警事件,recent 可见、clear 可清空
- [x] agent 告警配置样例随附,与启动参数约定一致;webhook 默认地址指向演示应用自身(9600 契约)
- [x] 无 agent/插件时端点行为明确(提示或空数据),不抛异常

## Comments

- 2026-08-09 已实现并验证(移植自 sb-skywalking 的 TraceAlertWebhookController / TraceAlertWebhookStore / TraceAlertVerifyController):
  - 触发端点:`/api/trace-alert-demo/slow?ms=`(默认 4000)、`/api/order/{id}`(8.5s)、`/api/export/report?ms=`、`/status/{code}?ms=`、`/.well-known/**`、`/api/exists/{id}`、`/inner/business-test/probe`、`/api/trace-alert-demo/error`(未捕获异常)、`/api/trace-alert-demo/http500`、`/api/trace-alert-demo/httpclient-httpbin?status=`(Apache HttpClient 4.5.13,spring-boot 管理版本)、对照组 `/api/trace-alert-demo/ok`。
  - webhook 接收器:`POST /inner/sw/trace-alert`(附加 `_receivedAt`,环形 ConcurrentLinkedDeque 存 50 条)+ `GET .../recent` + `POST .../clear`;接收器为纯应用侧能力,无 agent 亦可用。
  - 读口:`GET /statisticTraceAlert` 透出 agent traceAlert 节点——`config.*`(enabled/webhookResolvedUrl/规则原文)、`rules[].hitCount`、`httpWebhook.*`(totalAttempts/successCount/lastTraceId)、`dispatcher.*`(submitted/slow/error 计数);插件未挂载返回 plugin=absent 提示,挂载但告警未启用返回 enabled=false。
  - 配置样例:`src/main/resources/agent.trace-alert.config.sample`(9600 webhook 契约,与插件默认 `${WebPort:9600}` 一致,基本无需改动);`scripts/run-with-agent.ps1` 内置同源规则参数。
  - agent 实跑验证全绿:slow?ms=4000→SLOW、error→ERROR、`/api/order/1`→SLOW(Ant 规则)、`/status/500`→豁免无事件、`/api/trace-alert-demo/ok`→无事件、httpbin 500→ERROR(客户端 exit span)、recent 可见/clear 可清;无插件模式:statisticTraceAlert 返回提示,8 个触发端点状态码符合预期,webhook 手工投递/查询/清空可用,均无异常。
  - **关键发现 1(规则语义)**:Spring MVC 端点 operation 名恒带方法前缀(如 `GET:/status/{code}`),而 exit span 的 operation 为 uri 路径(如 `/status/500`,无前缀)。省略 METHOD 的规则 `operation:/status/*` 对入口 span 永远不匹配(豁免失效 → /status/500 持续误报),却会误匹配同名 exit span(httpbin 的 `/status/500` 被错误豁免)。修法:规则一律写全 METHOD(样例/脚本/Controller 注释已统一);插件侧 README"METHOD 可省略(匹配任意 HTTP 方法)"对 MVC 场景不成立,建议后续工单评估插件行为(如 pattern 无 METHOD 时剥离 operation 前缀)。
  - **关键发现 2**:httpbin.org 外部服务不稳(实测 503/500 抖动),应用透传其状态码;agent 验证时 503(≥500)同样触发 ERROR,无碍断言。
