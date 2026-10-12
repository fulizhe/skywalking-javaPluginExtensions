# RPC 调用排行演示压测（演示 / 讲解用，**手动执行**）
#
# 为什么单独一个脚本：RPC 造数与"稳定性压测"的目的不同。
#   - 稳定性压测：stress.ps1 默认档 / -AllEndpoints / -NormalOnly / -MaxRps
#   - 依赖面压测：stress.ps1 -WithDeps（含 RPC，但混着 Redis/MySQL/Kafka/外呼）
#   - RPC 排行演示（本脚本）：**只打 RPC**，把压力量集中到一个通道，让
#     /dashboards/rpc-topn.html 的"出站按方法排行 + 证据下钻"长出形状。
# 本脚本是 stress.ps1 的薄壳（同 stress-slow.ps1 的做法）：固定路径集 + 跑完打印 RPC 排行摘要。
#
# ★ demo 现状：RPC 挂在同一个 service 下的**三个方法**（进程内 gRPC，客户端
#   spanLayer=RPCFramework、组件 GRPC）：
#     demo.DepsDemo/Echo       （快，服务端模拟 5~40ms）
#     demo.DepsDemo/BatchSync  （中，50~200ms）
#     demo.DepsDemo/StreamData （慢，150~500ms）
#   一次 /api/deps-demo/grpc 请求会把这**三个方法各调一次**，于是排行页能出现真正的名次竞争
#   （默认按 P95 降序：StreamData > BatchSync > Echo）。
#   耗时是服务端**模拟**的（deps.grpc.simulateLatency=false 可关掉），旨在让分位/条形有形状。
#
# 造数端点：GET /api/deps-demo/grpc —— 进程内起一个最小 gRPC server，客户端把**三个方法各调一次**。
#   （各方法的服务端模拟耗时是**各自区间内均匀随机**，见上；可用 deps.grpc.simulateLatency=false 关掉。
#   不依赖任何外部中间件，任何机器都能跑。）
#
# 用法:
#   pwsh .\scripts\stress-rpc.ps1                        # 默认：2000 请求 / 16 线程
#   pwsh .\scripts\stress-rpc.ps1 -Requests 500          # 少打一点（演示够）
#   pwsh .\scripts\stress-rpc.ps1 -DurationSec 300       # 打 5 分钟，让进程累计长出量级
#   pwsh .\scripts\stress-rpc.ps1 -Continuous            # 无限模式，Ctrl+C 停（每 10s 打进度）
#   pwsh .\scripts\stress-rpc.ps1 -StartApp -KeepRunning # 顺带起应用（带 agent）并保留运行
#   pwsh .\scripts\stress-rpc.ps1 -SkipMetrics           # 不打印 RPC 排行摘要
#
# 跑完看效果：浏览器打开 http://127.0.0.1:9600/dashboards/rpc-topn.html
#   （或裸读口：curl.exe --noproxy "*" "http://127.0.0.1:9600/inner/sw/rpc-topn"）
param(
    [int]$Requests = 2000,
    [int]$Threads = 16,
    # 目标地址（应用需已在跑：run-with-agent.ps1；或加 -StartApp 让本脚本代为启动）
    [string]$BaseUrl = "http://127.0.0.1:9600",
    # 无限模式：死循环压，Ctrl+C 停（对齐 stress.ps1 -Continuous）
    [switch]$Continuous,
    # 时长模式：按秒压（>0 有效；被 -Continuous 覆盖）
    [int]$DurationSec = 0,
    # 进度打印间隔（默认 10s）
    [int]$ProgressSec = 10,
    # 单请求超时。RPC echo 是 ms 级，默认 10s 足够。
    [int]$TimeoutMs = 10000,
    # 跳过压测后的 RPC 排行摘要
    [switch]$SkipMetrics,
    # 透传给 stress.ps1：目标未运行时就地起应用（带 agent）
    [switch]$StartApp,
    # 透传给 stress.ps1：本脚本起的应用压完保留运行
    [switch]$KeepRunning,
    [string]$AgentDir = "",
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"

# RPC 造数端点：唯一一条（demo 目前只有一个 RPC 方法）。
$RPC_PATHS = "/api/deps-demo/grpc"
$base = $BaseUrl.TrimEnd('/')

$mode = if ($Continuous) { "无限(看累计增长,Ctrl+C 停)" } elseif ($DurationSec -gt 0) { "时长 ${DurationSec}s" } else { "请求数 $Requests" }
Write-Host ""
Write-Host "==== RPC 调用排行演示压测 ===="
Write-Host "目标      : $base"
Write-Host "模式      : $mode"
Write-Host "线程      : $Threads"
Write-Host "单请求超时: ${TimeoutMs}ms"
Write-Host "路径      : $RPC_PATHS  （一次请求调 3 个 gRPC 方法：Echo/BatchSync/StreamData；spanLayer=RPCFramework / 组件 GRPC）"
Write-Host "[--] 排行默认按 P95 降序：StreamData > BatchSync > Echo（服务端模拟耗时，档位拉开）。"
Write-Host ""

# 委派给 stress.ps1 执行真正的压测；RPC 摘要由本脚本自己打印，故让 stress 跳过它的 trace 指标摘要。
$sa = @("-NoProfile", "-File", (Join-Path $PSScriptRoot "stress.ps1"),
    "-BaseUrl", $BaseUrl, "-Paths", $RPC_PATHS, "-Threads", $Threads,
    "-TimeoutMs", $TimeoutMs, "-SkipMetrics")
if ($Continuous) { $sa += "-Continuous" }
elseif ($DurationSec -gt 0) { $sa += @("-DurationSec", $DurationSec) }
else { $sa += @("-Requests", $Requests) }
if ($Continuous -or $DurationSec -gt 0) { $sa += @("-ProgressSec", $ProgressSec) }
if ($StartApp) { $sa += "-StartApp" }
if ($KeepRunning) { $sa += "-KeepRunning" }
if ($AgentDir) { $sa += @("-AgentDir", $AgentDir) }
if ($JavaHome) { $sa += @("-JavaHome", $JavaHome) }

& pwsh @sa
$loadExit = $LASTEXITCODE

# ---- RPC 排行摘要（/inner/sw/rpc-topn）----
if (-not $SkipMetrics) {
    Write-Host ""
    Write-Host "---- RPC 调用排行 /inner/sw/rpc-topn（进程累计，按 P95 降序）----"
    try {
        if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
            $raw = & curl.exe -s --noproxy "*" --max-time 20 "$base/inner/sw/rpc-topn" 2>$null
            $j = (($raw -join "`n") | ConvertFrom-Json)
        } else {
            $j = Invoke-RestMethod "$base/inner/sw/rpc-topn" -TimeoutSec 20
        }
        if ($null -eq $j.enabled) {
            Write-Host "   读口未生效：响应里没有 enabled 字段（插件未加载 / 版本不匹配 / 跑的是旧实例）。"
            Write-Host "   原始响应: $((($j | ConvertTo-Json -Compress -Depth 4)))"
        } elseif (-not $j.enabled) {
            Write-Host "   指标聚合未启用（metrics.enabled=false）——排行无数据。"
        } elseif (-not $j.rows -or @($j.rows).Count -eq 0) {
            Write-Host "   暂无出站 RPC 记录。先访问一次 $base/api/deps-demo/grpc 再看。"
        } else {
            $rows = @($j.rows)
            Write-Host ("   {0,-4} {1,-10} {2,-34} {3,8} {4,9} {5,8} {6,8} {7,8} {8,8}" -f `
                "#", "组件", "方法", "次数", "错误率", "P50", "P95", "P99", "最大")
            $i = 0
            foreach ($r in $rows) {
                $i++
                $name = if ($r.componentName) { $r.componentName } else { "$($r.spanLayer)(#$($r.componentId))" }
                $p95 = if ($r.p95 -lt 0) { "-" } else { "$($r.p95)ms" }
                $p99 = if ($r.p99 -lt 0) { "-" } else { "$($r.p99)ms" }
                Write-Host ("   {0,-4} {1,-10} {2,-34} {3,8} {4,9} {5,8} {6,8} {7,8} {8,8}" -f `
                    $i, $name, $r.operation, $r.requestCount, ("{0:P2}" -f [double]$r.errorRate),
                    "$($r.p50)ms", $p95, $p99, "$($r.maxLatency)ms")
            }
            Write-Host "   页面: $base/dashboards/rpc-topn.html （点行看证据样本 → 下钻链路）"
        }
    } catch {
        Write-Host "[WARN] RPC 排行读口失败: $_"
    }
}

exit $loadExit
