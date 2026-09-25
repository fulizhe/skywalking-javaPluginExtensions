# 压测辅助:demon-app 接口负载 + agent 应用启停编排
#
# 压测实现是自包含的 Java 类(demo-app 测试源码 org.openskywalking.demo.load.HttpLoadTest,
# JUnit5,默认不参与 mvn test)。本脚本只做编排:起应用(可选) -> 跑压测 -> 打印指标摘要 -> 停应用(可选)。
#
# 三种模式:
#   1) 请求数模式(默认):发够 -Requests 个请求即结束
#      pwsh ./scripts/stress.ps1 -Requests 2000 -Threads 16
#   2) 时长模式:持续压 -DurationSec 秒
#      pwsh ./scripts/stress.ps1 -DurationSec 3600 -Threads 16
#   3) 无限模式(稳定性观测):死循环压测,周期性打印吞吐 + 插件自身计数 + JVM 堆,直到 Ctrl+C
#      pwsh ./scripts/stress.ps1 -Continuous -Threads 16
#
# 纯指标 vs 告警耦合(把"指标稳定性"与"告警稳定性"分开压):
#   - -NormalOnly:本脚本只压正常端点(排除 /error 与 /http500,含 /longTimeTask) -> 压"纯指标聚合"
#   - 告警开关属于"应用启动"这一层,由 run-with-agent.ps1 控制:
#       run-with-agent.ps1 -NoAlert  起"纯指标"实例(alert off)
#       run-with-agent.ps1           起"带告警"实例(默认 alert on,含 webhook 自环)
#   推荐组合:
#       纯指标稳定性 : run-with-agent.ps1 -NoAlert  +  stress.ps1 -NormalOnly
#       指标+告警耦合 : run-with-agent.ps1           +  stress.ps1(默认混合路径,含 error)
#   注意:stress.ps1 -StartApp 自身起的应用不带 alert(纯指标);直接压已在运行的实例时,
#         告警开关由该实例启动参数决定(-NoAlert / 默认 on)。
#
# 两种应用来源:
#   A) 应用已在运行(run-with-agent.ps1 保持运行 / validate*.ps1 -KeepRunning):直接压测
#      pwsh ./scripts/stress.ps1 -BaseUrl http://127.0.0.1:9601 -Paths "/hello,/fullSample"
#   B) 一条命令:起应用(带 agent) + 压测 + 摘要,压测后自动停应用
#      pwsh ./scripts/stress.ps1 -StartApp -Continuous -KeepRunning   # 无限压测并保留应用
#
# 说明:
#   - 无限模式下,HttpLoadTest 每 -ProgressSec 秒打印一行 [progress];其中 plugin= 段即插件
#     /inner/sw/metrics 的计数(rowsUpserted/lateDropped/sampleOverflow/persistErrors/aggregateErrors),
#     持续观察这些计数是否异常增长即可判断插件稳定性;heap= 用于观察是否内存泄漏。
#   - 指标是"分钟桶 + 30s 翻转":当前分钟桶已可通过读口合并内存窗口看到;7d/30d 需持续运行积累。
#   - 造数后浏览器打开 http://127.0.0.1:<port>/dashboards/metrics.html 查看。
#
# 退出码:0 全绿;1 前置/启动失败;5 压测失败。
param(
    [string]$BaseUrl = "http://127.0.0.1:9600",
    [int]$Requests = 1000,
    [int]$Threads = 8,
    # 逗号分隔的目标路径;留空用 HttpLoadTest 内置混合集(正常 + 错误 + 慢)
    [string]$Paths = "",
    [int]$TimeoutMs = 10000,
    # 时长模式:持续压测秒数(>0 生效;与 -Continuous 互斥)
    [int]$DurationSec = 0,
    # 无限模式:死循环压测直到 Ctrl+C(稳定性观测)
    [switch]$Continuous,
    # 持续模式下打印进度/插件计数的间隔秒数
    [int]$ProgressSec = 10,
    # 目标未运行时就地启动演示应用(带 agent)
    [switch]$StartApp,
    # 由本脚本启动应用时,压测后保留运行(否则自动停止)
    [switch]$KeepRunning,
    # 只压正常端点(排除 /error 与 /http500):压"纯指标聚合"(告警开关见 run-with-agent.ps1 -NoAlert)
    [switch]$NormalOnly,
    # 跳过压测后的指标摘要
    [switch]$SkipMetrics,
    # 指标摘要前等待秒数(给翻转线程一点时间)
    [int]$MetricWaitSec = 3,
    # 仅 -StartApp 生效
    [switch]$SkipPluginBuild,
    [switch]$SkipAppBuild,
    [string]$AgentDir = "",
    [string]$JavaHome = "",
    [string]$BuildJavaHome = ""
)

$ErrorActionPreference = "Stop"

$demoAppDir = Split-Path -Parent $PSScriptRoot          # agent/demo-app
$agentModuleDir = Split-Path -Parent $demoAppDir        # agent
$demoAppPom = Join-Path $demoAppDir "pom.xml"
$appJar = Join-Path $demoAppDir "target\demo-app-1.0.0.jar"
$pluginJarName = "logfile-reporter-plugin-2.0.0.jar"
$pluginJar = Join-Path $agentModuleDir "logfile-reporter-plugin\target\$pluginJarName"
$base = $BaseUrl.TrimEnd('/')
$port = ([uri]$base).Port
$script:startedProc = $null

# 正常端点集(排除 /error 与 /http500);用于 -NormalOnly 的"纯指标"压测
$NORMAL_PATHS = "/hello,/fullSample,/queryDbByMybatis,/queryDbByJdbc,/longTimeTask"
$effectivePaths = $Paths
if (-not $effectivePaths -and $NormalOnly) { $effectivePaths = $NORMAL_PATHS }
$pathsText = if ($effectivePaths) { $effectivePaths } else { "(HttpLoadTest 内置默认:混合,含 error)" }

function Get-DriveRoot { return 'D:' }

function Resolve-AgentDir {
    if ($AgentDir) { return $AgentDir }
    if ($env:SKYWALKING_AGENT_DIR) { return $env:SKYWALKING_AGENT_DIR }
    return "$(Get-DriveRoot)\apps\apache-skywalking-java-agent-9.4.0"
}

function Resolve-JavaHome {
    if ($JavaHome) { return $JavaHome }
    $jdk8Candidates = Get-ChildItem "$(Get-DriveRoot)\apps\java" -Directory -Filter "jdk1.8*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk8Candidates) { return $jdk8Candidates.FullName }
    if ($env:JAVA_HOME) { return $env:JAVA_HOME }
    throw "未找到 JDK。请用 -JavaHome 指定,或设置 JAVA_HOME。"
}

function Resolve-BuildJavaHome {
    if ($BuildJavaHome) { return $BuildJavaHome }
    $jdk17Candidates = Get-ChildItem "$(Get-DriveRoot)\apps\java" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^jdk[-_.]?17' } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk17Candidates) { return $jdk17Candidates.FullName }
    return $script:javaHome
}

function Test-HttpOnce($url) {
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
        $code = & curl.exe -s -o NUL -w "%{http_code}" --noproxy "*" --connect-timeout 3 --max-time 5 $url 2>$null
        return "$code" -eq "200"
    }
    try { return (Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200 } catch { return $false }
}

function Wait-Ready($url, $seconds) {
    for ($i = 0; $i -lt $seconds; $i++) {
        Start-Sleep -Seconds 1
        if (Test-HttpOnce $url) { return $true }
    }
    return $false
}

function Get-Json($url) {
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
        $raw = & curl.exe -s --noproxy "*" --max-time 20 $url 2>$null
        return (($raw -join "`n") | ConvertFrom-Json)
    }
    return Invoke-RestMethod $url -TimeoutSec 20
}

function Stop-StartedApp {
    if ($script:startedProc -and -not $script:startedProc.HasExited) {
        Stop-Process -Id $script:startedProc.Id -Force -ErrorAction SilentlyContinue
        $script:startedProc.WaitForExit(10000) | Out-Null
    }
}

# 就地启动演示应用(带 agent),返回 Process。裁剪自 run-with-agent.ps1,仅保留压测所需。
function Start-DemoApp {
    if (-not $SkipAppBuild) {
        Write-Host "[..] 构建 demo-app: mvn -f $demoAppPom clean package -DskipTests"
        Push-Location $demoAppDir
        mvn -f $demoAppPom clean package -DskipTests -q
        $code = $LASTEXITCODE
        Pop-Location
        if ($code -ne 0) { Write-Host "[FAIL] demo-app 构建失败 (exit=$code)"; exit 1 }
    }
    if (-not (Test-Path $appJar)) { Write-Host "[FAIL] demo-app jar 不存在: $appJar(去掉 -SkipAppBuild 重试)"; exit 1 }
    Write-Host "[OK] demo-app jar: $appJar"

    $agentDir = Resolve-AgentDir
    $agentJar = Join-Path $agentDir "skywalking-agent.jar"
    if (-not (Test-Path $agentJar)) { Write-Host "[FAIL] agent 目录无效(缺 skywalking-agent.jar): $agentDir"; exit 1 }
    Write-Host "[OK] agent: $agentDir"

    $script:javaHome = Resolve-JavaHome
    $javaExe = Join-Path $script:javaHome "bin\java.exe"
    if (-not (Test-Path $javaExe)) { Write-Host "[FAIL] 无效的 JDK 目录: $script:javaHome"; exit 1 }
    Write-Host "[OK] JDK: $script:javaHome"

    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "[FAIL] 端口 $port 已被占用。请先停止旧实例,或换 -BaseUrl。"
        exit 1
    }

    if (-not $SkipPluginBuild) {
        $buildJavaHome = Resolve-BuildJavaHome
        $env:JAVA_HOME = $buildJavaHome
        $env:PATH = "$buildJavaHome\bin;$env:PATH"
        Write-Host "[..] 构建插件: mvn clean package -Dmaven.test.skip=true -pl logfile-reporter-plugin -am (toolchain=$buildJavaHome)"
        Push-Location $agentModuleDir
        mvn clean package "-Dmaven.test.skip=true" -pl logfile-reporter-plugin -am -q
        $code = $LASTEXITCODE
        Pop-Location
        if ($code -ne 0) { Write-Host "[FAIL] 插件构建失败 (exit=$code)"; exit 1 }
    }
    if (-not (Test-Path $pluginJar)) { Write-Host "[FAIL] 插件 jar 不存在: $pluginJar(去掉 -SkipPluginBuild 重试)"; exit 1 }
    Copy-Item $pluginJar (Join-Path $agentDir "plugins\$pluginJarName") -Force
    Write-Host "[OK] 插件已装入: $agentDir\plugins\$pluginJarName"

    Remove-Item (Join-Path $agentDir "logs\skywalking-api.log") -Force -ErrorAction SilentlyContinue
    $swArgs = @(
        "-javaagent:$agentJar",
        "-Dskywalking.agent.keep_tracing=true",
        "-Dskywalking.plugin.logfilereporter.h2.enabled=true"
    )
    $env:WebPort = "$port"
    $outLog = Join-Path $demoAppDir "target\stress-out.log"
    $errLog = Join-Path $demoAppDir "target\stress-err.log"
    Write-Host "[..] 启动 demo-app (javaagent, WebPort=$port, alert=off(纯指标)): $base/"
    $proc = Start-Process -FilePath $javaExe -ArgumentList ($swArgs + @("-jar", $appJar)) `
        -WorkingDirectory $demoAppDir -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
    if (-not (Wait-Ready "$base/" 90)) {
        Write-Host "[FAIL] 应用 90 秒内未就绪。stderr 尾部:"
        Get-Content $errLog -ErrorAction SilentlyContinue | Select-Object -Last 15
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        exit 2
    }
    Write-Host "[OK] 应用就绪 (PID=$($proc.Id), 日志: $outLog)"
    return $proc
}

# ================= 主流程 =================

$mode = if ($Continuous) { "无限(稳定性观测)" } elseif ($DurationSec -gt 0) { "时长 ${DurationSec}s" } else { "请求数 $Requests" }
Write-Host "==============================================================="
Write-Host " demo-app 压测 (stress)"
Write-Host "   target=$base  mode=$mode  threads=$Threads  timeout=${TimeoutMs}ms"
Write-Host "   paths=$pathsText"
Write-Host "   startApp=$StartApp  normalOnly=$NormalOnly  keepRunning=$KeepRunning  skipMetrics=$SkipMetrics"
Write-Host "==============================================================="

if ($Threads -le 0) { Write-Host "[FAIL] -Threads 必须为正整数"; exit 1 }
if (-not $Continuous -and $DurationSec -le 0 -and $Requests -le 0) { Write-Host "[FAIL] -Requests 必须为正整数(或改用 -DurationSec / -Continuous)"; exit 1 }

$appRunning = Test-HttpOnce "$base/"
if (-not $appRunning) {
    Write-Host "[..] 目标未在运行: $base/"
    if (-not $StartApp) {
        Write-Host "[FAIL] 请先用 run-with-agent.ps1 启动应用,或加 -StartApp 让本脚本代为启动。"
        Write-Host "       pwsh ./scripts/run-with-agent.ps1 -SkipPluginBuild -Port $port"
        exit 1
    }
    $script:startedProc = Start-DemoApp
} else {
    Write-Host "[OK] 目标已在运行: $base/  (告警开关由该实例启动参数决定;纯指标请用 run-with-agent.ps1 -NoAlert 起实例)"
}

# ---- 跑压测(委托自包含 Java 类) ----
$mvnArgs = @(
    "-f", $demoAppPom, "test",
    "-Dtest=HttpLoadTest", "-Dloadtest=true",
    "-Dloadtest.baseUrl=$base",
    "-Dloadtest.threads=$Threads", "-Dloadtest.timeoutMs=$TimeoutMs",
    "-Dloadtest.progressSec=$ProgressSec"
)
if ($Continuous) { $mvnArgs += "-Dloadtest.durationSec=-1" }
elseif ($DurationSec -gt 0) { $mvnArgs += "-Dloadtest.durationSec=$DurationSec" }
else { $mvnArgs += "-Dloadtest.requests=$Requests" }
if ($Paths) { $mvnArgs += "-Dloadtest.paths=$Paths" }
Write-Host ""
Write-Host "[..] 运行压测: mvn -f $demoAppPom test -Dtest=HttpLoadTest -Dloadtest=true ..."
Write-Host ""
& mvn @mvnArgs
$loadExit = $LASTEXITCODE

# ---- 指标摘要 ----
if (-not $SkipMetrics) {
    if ($MetricWaitSec -gt 0) { Start-Sleep -Seconds $MetricWaitSec }
    Write-Host ""
    Write-Host "---- Trace 指标摘要 /inner/sw/metrics ----"
    try {
        $m = Get-Json "$base/inner/sw/metrics"
        Write-Host ("   enabled={0}  storageEnabled={1}  内存窗口桶={2}" -f $m.enabled, $m.storageEnabled, @($m.buckets).Count)
        $g = @($m.buckets) | Where-Object { $_.endpoint -eq '*' } | Sort-Object timeBucket -Descending | Select-Object -First 1
        if ($g) {
            Write-Host ("   全局最新桶 {0}: 请求={1} 错误={2} 慢={3} P50={4} P95={5} P99={6}" -f `
                $g.bucketStart, $g.requestCount, $g.errorCount, $g.slowCount, $g.p50, $g.p95, $g.p99)
        } else {
            Write-Host "   全局桶暂缺(可能无入口段流量,或指标未启用)"
        }
        Write-Host "   大屏: $base/dashboards/metrics.html"
    } catch {
        Write-Host "[WARN] 指标读口失败: $_"
    }
}

# ---- 收尾:仅当应用由本脚本启动时才处理 ----
if ($script:startedProc) {
    if ($KeepRunning) {
        Write-Host "[OK] 保留应用运行中 (PID=$($script:startedProc.Id))。停止: Stop-Process -Id $($script:startedProc.Id) -Force"
    } else {
        Stop-StartedApp
        Write-Host "[OK] 已停止本脚本启动的应用。"
    }
}

if ($loadExit -ne 0) {
    Write-Host ""
    Write-Host "[FAIL] 压测失败 (exit=$loadExit)"
    exit 5
}
Write-Host ""
Write-Host "[OK] 压测完成。"
exit 0
