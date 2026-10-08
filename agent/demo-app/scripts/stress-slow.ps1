# 慢端点专用压测（演示 / 讲解用，**手动执行**）
#
# 为什么单独一个脚本：慢端点与"稳定性压测"是**两种目的**，混在一个路径集里会互相污染。
#   - 稳定性压测：stress.ps1 默认档（只 ms 级）/ -AllEndpoints（求覆盖度）/ -NormalOnly
#   - 慢端点演示（本脚本）：看"慢在哪、慢到什么量级"，是给领导/同事**看现象**用的
# 把它们分开，慢端点就不会把稳定性压测的吞吐数字拖成一个没有参考价值的平均数。
#
# ★ **本档 = "所有慢端点"的唯一清单**。规则：`stress.ps1 -AllEndpoints`（60 条，覆盖度档，
#   含慢端点）里凡是单请求 >1s 的，**这里都要有一条对应项**。反过来只在这里、
#   `-AllEndpoints` 里没有的，是本档独有的造数手段（慢端点演示专用），不是漏配。
#   改动任一侧的路径集时按这条对账，别让"慢端点"只存在于其中一处。
#   实测基准（2026-10-02，Windows 本机，应用带 agent 运行中）——**客户端墙钟值**，含 JIT 与建连开销：
#     /api/export/report 裸调 65s → 本档用 ?ms={rand:1000,4000}（见下）
#     /api/order/1 8.5s ｜ /api/trace-alert-demo/slow?ms=4000 4.0s ｜ /debug 3.1s
#     /api/deps-demo/mysql?sleepMs=30 3.0s ｜ /fullSample 1.8s
#     /api/deps-demo/kafka?op=produce 1.7s ｜ /longTimeTask 1.2s
#   ⚠️ 面板上的分位是**服务端**耗时，比上面这组客户端值低。核对准确性请用下面这组服务端实测
#      （2026-10-08，108 容器内，本档 240 请求）：
#        GET:/api/order/{id}                         8.50s  ← 与客户端值一致
#        GET:/api/trace-alert-demo/slow?ms=4000      4.00s  ← 与客户端值一致
#        GET:/api/export/report?ms={rand:1000,4000}  P50 2.37s
#        GET:/debug                                  2.57s  ← 客户端量到 3.1s
#        GET:/longTimeTask                           0.99s  ← **随机 200~2000ms**(median≈1.1s)，非固定值
#        GET:/api/deps-demo/grpc                     0.03s  ← 客户端量到 0.3s，差一个数量级
#      ⚠️ 端点名以**指标**为准：压测打 `/api/order/1`，指标记录成 `GET:/api/order/{id}`
#         （Spring 把路径参数模板化了）。按 URL 名字在面板上找不到，不等于指标漏了。
#   ⚠️ 别按参数名估耗时：`?sleepMs=30` 实测 3.0s（H2 的 SLEEP 有秒级下限），
#     而 `/api/deps-demo/kafka?op=consume` 实测 0.31s（毫秒级，**不在**本档）。
#
# 这一档**会显著拉低吞吐**（单请求 0.03s ~ 8.5s），所以别拿它的 req/s 去和全量压测比。
#
# 用法:
#   pwsh .\scripts\stress-slow.ps1                      # 默认：25 次 / 4 线程
#   pwsh .\scripts\stress-slow.ps1 -Requests 10         # 只打 10 次（演示够了）
#   pwsh .\scripts\stress-slow.ps1 -Threads 1 -Requests 8 # 串行慢放，适合边讲边看
#   pwsh .\scripts\stress-slow.ps1 -DurationSec 300      # 打 5 分钟（让分位/趋势积累出形状）
#   pwsh .\scripts\stress-slow.ps1 -Continuous            # 无限模式，Ctrl+C 停（默认 10s 打一次进度）
#   pwsh .\scripts\stress-slow.ps1 -Continuous -ProgressSec 30
#   pwsh .\scripts\stress-slow.ps1 -SkipAlertDemo        # 不含告警演示端点（避免告警自环）
#   pwsh .\scripts\stress-slow.ps1 -All                 # 含告警演示端点（**会触发告警自环**）
#
# ⚠️ **告警自环**：`/api/trace-alert-demo/error`、`/http500` 这类端点会真的报错，
#    插件识别后经 webhook **同步回打本机**，而回打本身又是一次请求 —— 于是负载自我放大。
#    所以本脚本**默认不含**告警端点；要演示告警请显式加 `-All`（并预期吞吐下降、告警面板出现事件）。
#
# ⚠️ **无限模式看的是"数据在积累"，不是吞吐**：这一档单请求 0.03s~8.5s，req/s 天花板就很低
#    （8 线程约 1~2 req/s）。想看吞吐/堆/插件计数是否健康，用 stress.ps1 的 -Continuous。
param(
    [int]$Requests = 25,
    [int]$Threads = 4,
    # 目标地址（应用需已在跑：run-with-agent.ps1）
    [string]$BaseUrl = "http://172.16.1.108:9600",
    # 包含告警演示端点 → **会触发告警自环**
    [switch]$All,
    # 无限模式：死循环慢压，Ctrl+C 停（对齐 stress.ps1 -Continuous）
    [switch]$Continuous,
    # 时长模式：按秒压（>0 有效；被 -Continuous 覆盖）
    [int]$DurationSec = 0,
    # 进度打印间隔（默认 10s）
    [int]$ProgressSec = 10,
    # 单请求超时。**默认给到 30s**：本档最慢端点 8.5s（/api/order/1），并发下尾延迟会超过
    # stress.ps1 的 10s 默认值 —— 那样会攒出"网络异常"并让断言非零退出，而超时在慢端点压测里
    # 是预期现象，不是缺陷。
    [int]$TimeoutMs = 30000,
    # 跳过 demo-app 重新构建（复用已有 jar）
    [switch]$SkipAppBuild,
    # 跳过压测后的指标摘要
    [switch]$SkipMetrics,
    [string]$AgentDir = "",
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"

$demoAppDir = Split-Path -Parent $PSScriptRoot          # agent/demo-app

# 慢端点清单:标注单请求量级,便于讲解时对照。
# 量级取自实测(见 NOTES-docker-stress.md 与各端点实现),不是估的。
$SLOW_PATHS = [ordered]@{
    # ---- 毫秒级:SQL 与外部依赖,用来对比"同一进程内不同依赖的差距" ----
    "/api/deps-demo/mysql?sleepMs=1000" = "MySQL 侧慢查询 1s(依赖侧造慢边)"
    # 同一条路径的**另一档**参数：H2 的 SLEEP 有秒级下限,30ms 实测也要 3.0s。
    # 留两档是为了压测时能同时看到"1s 档"和"明显更慢档"的分位差异。
    "/api/deps-demo/mysql?sleepMs=30"   = "MySQL 慢查询 30ms 档(实测约 3.0s —— H2 的 SLEEP 有秒级下限,别按参数值估耗时)"
    "/api/deps-demo/kafka?op=produce"     = "Kafka 生产(broker 不可达时约 3s 超时,见拓扑页 caveats)"
    "/api/deps-demo/http?site=httpbin"   = "外呼 httpbin 约 1~2s(网络往返)"
    "/api/deps-demo/grpc"                = "进程内 gRPC **毫秒级**(容器内实测 P50 0.03s;首次建链约 4s)"
    # 不带 phase 参数：它是给断言区分回环阶段的，压测用不上。
    # （早先这里写成 ?value=v&phase=P，而 `&` 会被 Windows 的 cmd.exe 当命令分隔符 —— 见 stress.ps1 里的说明）
    "/api/hutool-demo/post-json?value=v" = "Hutool 出口回环自调(毫秒级,作为对照基线)"
    "/queryDbByMybatis"                   = "MyBatis → H2(毫秒级基线)"
    # ---- 秒级:业务慢端点,用来触发 SLOW 告警 ----
    "/api/trace-alert-demo/slow?ms=4000" = "睡 4s,超过默认 SLOW 阈值 3s → 触发 SLOW 告警"
    "/api/order/1"                        = "睡 8.5s,命中 Ant 规则 /api/order/*=8000 → SLOW(指标里为 GET:/api/order/{id})"
    # 该端点**默认 sleep 65s**（Ant 规则 /api/export/** 用它演示极端慢）。
    # 这里用 {rand:1000,4000} 让**每个请求随机睡 1~4s**：一条路径就能压出连续的耗时分布，
    # 而写死 3s 只能压出一条线、塞 4 个变体又会让它在路径列表里占 4 席带偏整体流量。
    # 占位符由 HttpLoadTest 替换；不替换的话 Spring 绑 int 失败会直接 400（不会悄悄通过）。
    # 演示要"65s 那一版"就直接开 /api/export/report（不带参数）。
    "/api/export/report?ms={rand:1000,4000}" = "慢导出(默认 65s;这里每请求随机睡 1~4s,仍命中 Ant /api/export/**)"
    "/longTimeTask"                       = "长任务(**随机 200~2000ms**,median≈1.1s;容器内实测 P50 0.99s —— 不是固定值)"
    "/debug"                              = "随机(RandomUtil.randomLong(2000) + profile2 递归);容器内实测 P50 2.57s"
    "/fullSample"                         = "全貌入口:约 5s(中间件未起时),且**会带上三层依赖**"
}

# 告警演示端点(慢 + 报错)。默认不参与,避免自环;显式 -All 时才加。
$ALERT_DEMO = [ordered]@{
    "/api/trace-alert-demo/error" = "抛异常 → ERROR 告警(**会触发 webhook 自环**)"
    "/api/trace-alert-demo/http500" = "HTTP 500 → ERROR 告警(**会触发 webhook 自环**)"
}

$groups = [ordered]@{}
foreach ($k in $SLOW_PATHS.Keys) { $groups[$k] = $SLOW_PATHS[$k] }
if ($All) { foreach ($k in $ALERT_DEMO.Keys) { $groups[$k] = $ALERT_DEMO[$k] } }

$paths = ($groups.Keys -join ",")

$mode = if ($Continuous) { "无限(看数据积累,Ctrl+C 停)" } elseif ($DurationSec -gt 0) { "时长 ${DurationSec}s" } else { "请求数 $Requests" }
Write-Host ""
Write-Host "==== 慢端点演示压测 ===="
Write-Host "目标      : $BaseUrl"
Write-Host "模式      : $mode"
Write-Host "线程      : $Threads"
Write-Host "单请求超时: ${TimeoutMs}ms"
if ($Continuous -or $DurationSec -gt 0) {
    Write-Host "进度打印  : 每 ${ProgressSec}s"
    Write-Host "[--] 本档单请求 0.3s~8.5s，req/s 天花板极低（看着像'卡住'是正常的 —— 它在等慢端点）"
}
Write-Host "端点数    : $($groups.Count)（含告警演示端点: $(if ($All) { '是' } else { '否' })）"
Write-Host ""
Write-Host "端点清单（含单请求量级，便于讲解对照）:"
foreach ($k in $groups.Keys) { Write-Host ("  {0,-46} {1}" -f $k, $groups[$k]) }
Write-Host ""
if ($All) {
    Write-Host "[!!] 已包含告警演示端点 → **会触发告警自环**：错误请求经插件 webhook 同步回打本机，"
    Write-Host "     回打本身又是一次请求，负载会自我放大。吞吐下降属预期。"
} else {
    Write-Host "[ok] 未包含告警演示端点 → 不会触发告警自环（要演示告警请加 -All）"
}
Write-Host "[--] 这一档看的是'慢在哪、慢到什么量级'；吞吐数字**不要**和 -AllEndpoints / -NormalOnly 比。"
Write-Host ""

$args = @("-NoProfile", "-File", (Join-Path $PSScriptRoot "stress.ps1"),
    "-BaseUrl", $BaseUrl, "-Requests", $Requests, "-Threads", $Threads, "-Paths", $paths,
    "-TimeoutMs", $TimeoutMs)
# 三种模式互斥，与 stress.ps1 同一套语义：无限 > 时长 > 请求数
if ($Continuous) { $args += "-Continuous" }
elseif ($DurationSec -gt 0) { $args += "-DurationSec", $DurationSec }
if ($Continuous -or $DurationSec -gt 0) { $args += "-ProgressSec", $ProgressSec }
if ($SkipAppBuild) { $args += "-SkipAppBuild" }
if ($SkipMetrics) { $args += "-SkipMetrics" }
if ($AgentDir) { $args += @("-AgentDir", $AgentDir) }
if ($JavaHome) { $args += @("-JavaHome", $JavaHome) }

& pwsh @args
exit $LASTEXITCODE
