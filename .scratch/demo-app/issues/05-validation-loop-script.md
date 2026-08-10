# 05 — 一键验证回路脚本

**What to build:** 验证回路的完整闭环:一条命令完成构建插件、安装、启动、造数、断言、报告。断言覆盖核心三件——trace 缓存合并、告警链路(含 webhook 收讫计数)、运行时开关;全绿退出码 0,失败大声且指明失败点。

**Blocked by:** 02 — 运行底座:agent 装配与启动脚本;03 — 统计快照读口移植;04 — Trace 告警验证面移植

**Status:** done

- [x] 一条命令完成 build → copy → start → 造数 → 断言 → 报告
- [x] 核心三件断言: trace 缓存合并 / 告警链(webhook 收讫计数)/ 运行时开关
- [x] 全绿退出码 0;任一断言失败非零退出,报告指明失败点
- [x] 插件未安装时脚本大声失败(非零退出、原因明确)——负向可测
- [x] 提供 IDE 手动模式参数供调试(与脚本模式同参)

## Comments

- 2026-08-09 已实现并验证:
  - `scripts/validate.ps1`(PowerShell,建议 pwsh 运行):一条命令完成 前置检查(agent/JDK/端口) → 构建(demo-app jar 缺失时 + logfile-reporter-plugin)→ 安装进 agent plugins → 清 agent 日志 → `-javaagent` 启动 → 就绪 → 插件加载验证(agent 日志)→ 造数 → 断言 → 报告 → 停止应用。退出码:0 全绿 / 1 前置失败 / 2 未就绪 / 3 插件未加载 / 5 断言失败。
  - 断言 A(trace 缓存合并):新增本地自调用端点 `GET /api/trace-alert-demo/self-call`(经 Apache HttpClient 请求应用自身 `/ok`,不依赖外部网络)——同一 traceId 下确定性产生入口+出口 2 个 segment;断言同一 traceId 下 logs>=2、segment traceId 一致且等于缓存键、traceSegmentId 互异、span 字段齐全(spanId/parentSpanId/operationName/时间/spanType/spanLayer/componentId/isError)、Entry+Exit 双类型、入口为 self-call 且出口指向应用自身。
  - 断言 B(告警链端到端):slow?ms=4000(默认阈值)→SLOW、`/api/order/1`(Ant 规则 8000)→SLOW、`/api/trace-alert-demo/error`(isError)→ERROR、`/api/trace-alert-demo/http500`(HTTP 500)→ERROR、`/status/500`(豁免 503,500,400)→无事件、`/api/trace-alert-demo/ok`(对照)→无事件;webhook 收讫 >=4 事件、`httpWebhook.totalAttempts>=4` 且 `successCount>=4`(端到端闭环)、`dispatcher.dispatchSubmitted>=4`。
  - 断言 C(运行时开关):disable 后 3 次造数增长 <=2(自读噪声),enable 后 >=3。
  - 断言 D(冒烟):JVM/meter/日志/实例属性四流有数据 + 告警运行态 enabled。
  - 实测:正向一轮全绿 24 项断言 exit 0(约 4 分钟,含 8.5s 慢请求与开关等待);负向 `-SkipPluginInstall`(移除插件 jar 启动)在插件加载检查处大声失败 exit 3,原因明确。
  - IDE 手动模式即 `scripts/run-with-agent.ps1`,与 validate.ps1 共享同一套参数(`-AgentDir`/`-JavaHome`/`-SkipPluginBuild`/`-Port`)与同一份启动参数(-javaagent + -Dskywalking.* + WebPort),脚本头有指引。
  - 实现要点:造数请求用 Send-Traffic 忽略响应状态码(4xx/5xx 端点本就是断言对象,`$ErrorActionPreference=Stop` 下 Invoke-WebRequest 会抛异常);告警事件异步投递,收讫断言带轮询等待(最长 40s)。
- 2026-08-10 就绪探测加固:实测用户环境出现"浏览器能开页面、脚本 90s 探测失败"现象——应用日志证明应用已就绪且服务过一次请求(即用户浏览器那次),而脚本侧 Invoke-WebRequest 连续 90s 拿不到 200。复现实验(.NET 对回环永远绕过代理,死代理下仍 200)排除了代理因素,定位为 PowerShell 探测路径受环境影响的偶发问题。修法:Test-Ready 优先用 `curl.exe --noproxy "*" --connect-timeout 3 --max-time 5`(Windows 10 1803+ 自带,不走 .NET/IE 代理、不受 Profile 默认参数覆盖),兜底 Invoke-WebRequest(超时 2s→5s,容纳 agent 冷启动首请求);失败时新增诊断——区分"TCP 在听但探测不通"与"应用未启动",分别打印 stderr/stdout 尾部。validate.ps1 与 run-with-agent.ps1 同步修复,回归全绿。
- 2026-08-10 二次修复(根因收口):就绪改为 curl 后,下一个 IWR 调用(断言 A self-call)爆出 502 Bad Gateway,而应用日志无该请求到达记录——证明 502 出自代理而非应用。此前"-NoProfile 干净环境 0 失败"复现不了,是因为交互式 profile 给 Invoke-WebRequest/RestMethod 注入了默认 `Proxy` 参数(显式 -Proxy 连回环也强制走代理),代理够不到客户端 127.0.0.1 → 502。最初"90s 未就绪"与本次 502 同根同源:就绪探测与造数都走被注入的代理。修法:本地 HTTP 全部收敛到 `Invoke-LocalHttp`(curl.exe `--noproxy "*"` 直连,兜底 IWR),Get-Json/Send-Traffic/self-call/clear/toggle 全部改走该函数。用 `$PSDefaultParameterValues['Invoke-WebRequest:Proxy']='http://127.0.0.1:9'` + RestMethod 同款注入模拟 profile 最坏场景,24/24 断言通过、exit 0。
