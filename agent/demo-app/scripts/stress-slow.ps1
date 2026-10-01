# 慢端点专用压测（演示 / 讲解用，**手动执行**）
#
# 为什么单独一个脚本：慢端点与"稳定���压测"是**两种目的**，混在一个路径集里会互相污染。
#   - 稳定��压测：`-AllEndpoints` / `-NormalOnly`，看吞吐、堆、插件计数是否健康
#   - 慢端点演示（本脚本）：看"慢在哪、慢到什么量级"，是给领导/同事**看现象**用的
# 把它们分开，慢端点就不会把稳定性压测的吞吐数字拖成一个没有参考价值的平均数。
#
# 这一档**会显著拉低吞吐**（单请求 0.3s ~ 8.5s），所以别拿它的 req/s 去和全量压测比。
#
# 用法:
#   pwsh .\scripts\stress-slow.ps1                      # 默认：25 次 / 4 线程
#   pwsh .\scripts\stress-slow.ps1 -Requests 10         # 只打 10 次（演示够了）
#   pwsh .\scripts\stress-slow.ps1 -Threads 1 -Requests 8 # 串行慢放，适合边讲边看
#   pwsh .\scripts\stress-slow.ps1 -SkipAlertDemo        # 不含告警演示端点（避免告警自环）
#   pwsh .\scripts\stress-slow.ps1 -All                 # 含告警演示端点（**会触发告警自环**）
#
# ⚠️ **告警自环**：`/api/trace-alert-demo/error`、`/http500` 这类端点会真的报错，
#    插件识别后经 webhook **同步回打本机**，而回打本身又是一次请求 —— 于是负载自我放大。
#    所以本脚本**默认不含**告警端点；要演示告警请显式加 `-All`（并预期吞吐下降、告警面板出现事件）。
param(
    [int]$Requests = 25,
    [int]$Threads = 4,
    # 目标地址（应用需已在跑：run-with-agent.ps1）
    [string]$BaseUrl = "http://127.0.0.1:9600",
    # 包含告警演示端点 → **会触发告警自环**
    [switch]$All,
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
    "/api/deps-demo/kafka?op=produce"     = "Kafka 生产(broker 不可达时约 3s 超时,见拓扑页 caveats)"
    "/api/deps-demo/http?site=httpbin"   = "外呼 httpbin 约 1~2s(网络往返)"
    "/api/deps-demo/grpc"                = "进程内 gRPC 约 0.3s(首次建链约 4s)"
    "/api/hutool-demo/post-json?value=v&phase=P" = "Hutool 出口回环自调(毫秒级,作为对照基线)"
    "/queryDbByMybatis"                   = "MyBatis → H2(毫秒级基线)"
    # ---- 秒级:业务慢端点,用来触发 SLOW 告警 ----
    "/api/trace-alert-demo/slow?ms=4000" = "睡 4s,超过默认 SLOW 阈值 3s → 触发 SLOW 告警"
    "/api/order/1"                        = "睡 8.5s,命中 Ant 规则 /api/order/*=8000 → SLOW"
    "/api/export/report"                  = "慢导出(秒级)"
    "/longTimeTask"                       = "长任务(约 1s)"
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

Write-Host ""
Write-Host "==== 慢端点演示压测 ===="
Write-Host "目标      : $BaseUrl"
Write-Host "次数/并发 : $Requests 次 / $Threads 线程"
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
    "-BaseUrl", $BaseUrl, "-Requests", $Requests, "-Threads", $Threads, "-Paths", $paths)
if ($SkipAppBuild) { $args += "-SkipAppBuild" }
if ($SkipMetrics) { $args += "-SkipMetrics" }
if ($AgentDir) { $args += @("-AgentDir", $AgentDir) }
if ($JavaHome) { $args += @("-JavaHome", $JavaHome) }

& pwsh @args
exit $LASTEXITCODE
