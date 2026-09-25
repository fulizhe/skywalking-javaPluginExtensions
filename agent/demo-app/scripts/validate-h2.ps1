# H2 影子存储验证:启动 agent + H2 影子路径 + compare_debug + 对账端点冒烟
#
# 本脚本在 validate.ps1 的基础上聚焦 H2 影子存储验证:
#   1) 以 h2.enabled=true + compare_debug=true 启动 demo-app
#   2) 验证 agent 日志出现 "H2TraceSegmentStorage initialized"(H2 驱动注册 + 建表成功)
#   3) 造数后等待 ≥6s(对账限频窗口),调用 /inner/sw/trace-parity 验证对账端点
#   4) 断言:h2Enabled=true、h2Size>0、checkedCount>0、totalDiffs 有值
#   5) 可选 -KeepRunning 保持运行,手动访问 H2 Web Console 或仪表盘
#
# 用法(pwsh 7,UTF-8):
#   pwsh ./scripts/validate-h2.ps1                    # 全流程,端口 9600
#   pwsh ./scripts/validate-h2.ps1 -SkipPluginBuild   # 插件已构建
#   pwsh ./scripts/validate-h2.ps1 -SkipAppBuild      # 复用已有 demo-app jar
#   pwsh ./scripts/validate-h2.ps1 -KeepRunning      # 验证后保持运行(手动观察)
#   pwsh ./scripts/validate-h2.ps1 -ConsoleEnabled    # 同时启动 H2 Web Console
#   pwsh ./scripts/validate-h2.ps1 -Port 9601 -ConsolePort 8093
#
# 退出码:0 全绿;1 前置失败;2 应用未就绪;3 插件未加载;4 H2 init 失败;5 断言失败
param(
    [string]$AgentDir = "",
    [string]$JavaHome = "",
    [string]$BuildJavaHome = "",
    [switch]$SkipPluginBuild,
    [switch]$SkipAppBuild,
    [switch]$KeepRunning,
    [switch]$ConsoleEnabled,
    [int]$ConsolePort = 8092,
    [int]$Port = 9600
)

$ErrorActionPreference = "Stop"

$demoAppDir = Split-Path -Parent $PSScriptRoot          # agent/demo-app
$agentModuleDir = Split-Path -Parent $demoAppDir        # agent
$pluginJarName = "logfile-reporter-plugin-2.0.0.jar"
$appJar = Join-Path $demoAppDir "target\demo-app-1.0.0.jar"
$pluginJar = Join-Path $agentModuleDir "logfile-reporter-plugin\target\$pluginJarName"
$outLog = Join-Path $demoAppDir "target\validate-h2-out.log"
$errLog = Join-Path $demoAppDir "target\validate-h2-err.log"
$base = "http://127.0.0.1:$Port"
$script:appProc = $null
$script:failures = 0

function Get-DriveRoot {
    return 'D:'
}

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
    return $javaHome
}

function Test-Release8Capable([string]$jdkHome) {
    $verLine = & (Join-Path $jdkHome "bin\java.exe") -version 2>&1 | Select-Object -First 1
    return ($verLine -match '"(\d+)' -and [int]$Matches[1] -ge 9)
}

function Assert($name, $cond, $detail) {
    if ($cond) { Write-Host "[PASS] $name" }
    else { $script:failures++; Write-Host "[FAIL] $name`n       $detail" }
}

function Test-HttpOnce($url) {
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
        $code = & curl.exe -s -o NUL -w "%{http_code}" --noproxy "*" --connect-timeout 3 --max-time 5 $url 2>$null
        return "$code" -eq "200"
    }
    try {
        $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
        return $r.StatusCode -eq 200
    } catch { return $false }
}

function Test-Ready($url, $seconds) {
    for ($i = 0; $i -lt $seconds; $i++) {
        Start-Sleep -Seconds 1
        if (Test-HttpOnce $url) { return $true }
    }
    return $false
}

function Invoke-LocalHttp($path) {
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
        $raw = @(& curl.exe -s -o - "--noproxy" "*" "-w" "`n%{http_code}" --max-time 30 "$base$path" 2>$null)
        $code = $raw[-1]
        $body = if ($raw.Count -gt 1) { ($raw[0..($raw.Count - 2)] -join "`n") } else { "" }
        return @{ StatusCode = "$code"; Content = $body }
    }
    $r = Invoke-WebRequest "$base$path" -UseBasicParsing -TimeoutSec 30
    return @{ StatusCode = "$($r.StatusCode)"; Content = "$($r.Content)" }
}

function Stop-App {
    if ($script:appProc -and -not $script:appProc.HasExited) {
        Stop-Process -Id $script:appProc.Id -Force -ErrorAction SilentlyContinue
        $script:appProc.WaitForExit(10000) | Out-Null
    }
}

Write-Host "==============================================================="
Write-Host " H2 影子存储验证 (validate-h2)"
Write-Host "   port=$Port  console=$ConsoleEnabled  keepRunning=$KeepRunning"
Write-Host "   skipPluginBuild=$SkipPluginBuild  skipAppBuild=$SkipAppBuild"
Write-Host "==============================================================="

try {
    # ---- 1. 前置检查 ----
    $agentDir = Resolve-AgentDir
    $agentJar = Join-Path $agentDir "skywalking-agent.jar"
    if (-not (Test-Path $agentJar)) {
        Write-Host "[FAIL] agent 目录无效(缺 skywalking-agent.jar): $agentDir"
        exit 1
    }
    Write-Host "[OK] agent: $agentDir"

    $javaHome = Resolve-JavaHome
    $javaExe = Join-Path $javaHome "bin\java.exe"
    if (-not (Test-Path $javaExe)) {
        Write-Host "[FAIL] 无效的 JDK 目录: $javaHome"
        exit 1
    }
    Write-Host "[OK] JDK: $javaHome"

    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "[FAIL] 端口 $Port 已被占用。请先停止旧实例,或换 -Port。"
        exit 1
    }

    # ---- 2. 构建 ----
    if (-not $SkipAppBuild) {
        Write-Host "[..] 构建 demo-app: mvn -f $demoAppDir\pom.xml clean package -DskipTests"
        Push-Location $demoAppDir
        mvn -f "$demoAppDir\pom.xml" clean package -DskipTests -q
        $code = $LASTEXITCODE
        Pop-Location
        if ($code -ne 0) { Write-Host "[FAIL] demo-app 构建失败 (exit=$code)"; exit 1 }
    }
    if (-not (Test-Path $appJar)) { Write-Host "[FAIL] demo-app jar 不存在: $appJar(请去掉 -SkipAppBuild)"; exit 1 }
    Write-Host "[OK] demo-app jar: $appJar"

    $agentPluginJar = Join-Path $agentDir "plugins\$pluginJarName"
    if (-not $SkipPluginBuild) {
        $buildJavaHome = Resolve-BuildJavaHome
        if (-not (Test-Release8Capable $buildJavaHome)) {
            Write-Host "[FAIL] 插件构建工具链需 JDK 9+($buildJavaHome 不支持 release 8)。请用 -BuildJavaHome 指定 JDK 17。"
            exit 1
        }
        $env:JAVA_HOME = $buildJavaHome
        $env:PATH = "$buildJavaHome\bin;$env:PATH"
        Write-Host "[..] 构建插件: mvn clean package -Dmaven.test.skip=true -pl logfile-reporter-plugin -am (toolchain=$buildJavaHome)"
        Push-Location $agentModuleDir
        mvn clean package "-Dmaven.test.skip=true" -pl logfile-reporter-plugin -am -q
        $code = $LASTEXITCODE
        Pop-Location
        if ($code -ne 0) { Write-Host "[FAIL] 插件构建失败 (exit=$code)"; exit 1 }
    }
    if (-not (Test-Path $pluginJar)) {
        Write-Host "[FAIL] 插件 jar 不存在: $pluginJar(请先构建或去掉 -SkipPluginBuild)"
        exit 1
    }
    Copy-Item $pluginJar $agentPluginJar -Force
    Write-Host "[OK] 插件已装入: $agentPluginJar"

    # ---- 3. 清空日志,启动应用(带 H2 参数)----
    Remove-Item (Join-Path $agentDir "logs\skywalking-api.log") -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $agentDir "logs\skywalking-agent.log") -Force -ErrorAction SilentlyContinue

    $swArgs = @(
        "-javaagent:$agentJar",
        "-Dskywalking.agent.keep_tracing=true",
        "-Dskywalking.plugin.logfilereporter.alert.enabled=true",
        "-Dskywalking.plugin.logfilereporter.alert.slow_rules=operation:GET:/status/*=8000;operation:GET:/api/order/*=8000",
        "-Dskywalking.plugin.logfilereporter.alert.error_ignore_rules=operation:GET:/status/*=503,500,400",
        # H2 影子存储:默认已 enabled=true,显式声明便于日志可追溯
        "-Dskywalking.plugin.logfilereporter.h2.enabled=true",
        # 对账比对:开启,5s 一轮
        "-Dskywalking.plugin.logfilereporter.h2.compare_debug=true",
        "-Dskywalking.plugin.logfilereporter.h2.shadow_max_rows=2000"
    )
    if ($ConsoleEnabled) {
        $swArgs += "-Dskywalking.plugin.logfilereporter.h2.console_enabled=true"
        $swArgs += "-Dskywalking.plugin.logfilereporter.h2.console_port=$ConsolePort"
    }

    $env:WebPort = "$Port"
    Write-Host "[..] 启动 demo-app (H2 shadow + compare_debug, WebPort=$Port): $base/"
    if ($ConsoleEnabled) {
        Write-Host "     H2 Web Console: http://127.0.0.1:$ConsolePort (jdbc:h2:mem:sw_trace_segment;DB_CLOSE_DELAY=-1, sa/<empty>)"
    }
    $script:appProc = Start-Process -FilePath $javaExe -ArgumentList ($swArgs + @("-jar", $appJar)) `
        -WorkingDirectory $demoAppDir -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru

    # ---- 4. 就绪检查 ----
    if (-not (Test-Ready "$base/" 90)) {
        Write-Host "[FAIL] 应用 90 秒内未就绪。"
        Get-Content $errLog -ErrorAction SilentlyContinue | Select-Object -Last 15
        Stop-App
        exit 2
    }
    Write-Host "[OK] 应用就绪"

    # ---- 5. 插件加载验证 ----
    $apiLog = Join-Path $agentDir "logs\skywalking-api.log"
    Start-Sleep -Seconds 2
    $loaded = Select-String -Path $apiLog -Pattern "logfile-reporter-plugin-[\d.]+\.jar loaded" -ErrorAction SilentlyContinue
    if (-not $loaded) {
        Write-Host "[FAIL] agent 日志未出现插件加载记录"
        Get-Content $apiLog -ErrorAction SilentlyContinue | Select-Object -Last 10
        Stop-App
        exit 3
    }
    Write-Host "[OK] 插件加载已验证: $($loaded[0].Line.Trim())"

    # ---- 6. H2 初始化验证(核心断言)----
    Write-Host ""
    Write-Host "---- H2 影子存储初始化验证 ----"
    $h2Init = Select-String -Path $apiLog -Pattern "H2TraceSegmentStorage initialized" -ErrorAction SilentlyContinue
    Assert "H2 驱动注册 + 建表成功(init 日志出现)" ($null -ne $h2Init) "agent 日志未见 H2TraceSegmentStorage initialized"

    $h2Error = Select-String -Path $apiLog -Pattern "No suitable driver|init error|H2Shadow.*error" -ErrorAction SilentlyContinue
    Assert "无 H2 初始化错误(No suitable driver / init error)" ($null -eq $h2Error) "发现 H2 错误: $($h2Error | ForEach-Object { $_.Line })"

    $compareDebugOn = Select-String -Path $apiLog -Pattern "compare_debug is ON" -ErrorAction SilentlyContinue
    Assert "compare_debug 已开启(限频 5s)" ($null -ne $compareDebugOn) "agent 日志未见 compare_debug is ON"

    if ($ConsoleEnabled) {
        $consoleStarted = Select-String -Path $apiLog -Pattern "Web Console started" -ErrorAction SilentlyContinue
        Assert "H2 Web Console 已启动(port=$ConsolePort)" ($null -ne $consoleStarted) "agent 日志未见 Web Console started"
    }

    # ---- 7. 造数:产生 trace 流量,让 H2 影子写入 + 对账触发 ----
    Write-Host ""
    Write-Host "---- 造数(产生 trace 流量)----"
    $null = Invoke-LocalHttp "/api/trace-alert-demo/self-call"
    Write-Host "  self-call sent"
    Start-Sleep -Seconds 1
    $null = Invoke-LocalHttp "/api/trace-alert-demo/slow?ms=4000"
    Write-Host "  slow request sent"
    Start-Sleep -Seconds 1
    $null = Invoke-LocalHttp "/api/trace-alert-demo/error"
    Write-Host "  error request sent"

    # ---- 8. 等待对账触发(限频 ≥5s,等 6s 确保一轮完成)----
    Write-Host ""
    Write-Host "---- 等待对账触发(6s ≥ 5s 限频窗口)----"
    Start-Sleep -Seconds 6

    # ---- 9. 对账端点验证 ----
    Write-Host ""
    Write-Host "---- 对账端点 /inner/sw/trace-parity ----"
    $parityResp = Invoke-LocalHttp "/inner/sw/trace-parity"
    Assert "对账端点返回 HTTP 200" ("$($parityResp.StatusCode)" -eq "200") "statusCode=$($parityResp.StatusCode)"

    if ($parityResp.Content) {
        try {
            $parity = $parityResp.Content | ConvertFrom-Json
        } catch {
            $parity = $null
        }
    } else {
        $parity = $null
    }

    if ($parity) {
        Assert "h2Enabled=true" ($parity.h2Enabled -eq $true) "h2Enabled=$($parity.h2Enabled)"
        Assert "compareDebug=true" ($parity.compareDebug -eq $true) "compareDebug=$($parity.compareDebug)"
        Assert "h2Size > 0(H2 影子库有数据)" ($parity.h2Size -gt 0) "h2Size=$($parity.h2Size)"
        Assert "checkedCount > 0(对账已触发)" ($parity.checkedCount -gt 0) "checkedCount=$($parity.checkedCount)"
        Assert "h2ErrorCount = 0(无 H2 错误)" ($parity.h2ErrorCount -eq 0) "h2ErrorCount=$($parity.h2ErrorCount)"
        Assert "writeQueueDropped 可读(异步写队列丢弃计数)" ($null -ne $parity.writeQueueDropped) "缺少 writeQueueDropped 字段"

        Write-Host ""
        Write-Host "  对账快照:"
        Write-Host "    h2Enabled      = $($parity.h2Enabled)"
        Write-Host "    compareDebug   = $($parity.compareDebug)"
        Write-Host "    checkedCount   = $($parity.checkedCount)"
        Write-Host "    totalDiffs      = $($parity.totalDiffs)"
        Write-Host "    h2Size         = $($parity.h2Size)"
        Write-Host "    h2ErrorCount   = $($parity.h2ErrorCount)"
        Write-Host "    writeQueueDropped = $($parity.writeQueueDropped)"
    } else {
        $script:failures++
        Write-Host "[FAIL] 对账端点返回不可解析的 JSON"
        if ($parityResp.Content) { Write-Host "       body: $($parityResp.Content.Substring(0, [Math]::Min(200, $parityResp.Content.Length)))" }
    }

    # ---- 9b. 环形载荷文件 + 查询门面(使用侧人工验证的读口) ----
    Write-Host ""
    Write-Host "---- 环形载荷文件 + 查询门面 ----"
    $cappedFile = Join-Path $demoAppDir "trace-payload.capped.db"
    Assert "环形载荷文件已创建" (Test-Path $cappedFile) "未找到 $cappedFile"
    if (Test-Path $cappedFile) {
        $cappedLen = (Get-Item $cappedFile).Length
        Assert "环形文件定长 128MB" ($cappedLen -eq 134217728) "size=$cappedLen"
    }

    $recentResp = Invoke-LocalHttp "/inner/sw/trace-recent?limit=20"
    Assert "最近链路列表 HTTP 200" ("$($recentResp.StatusCode)" -eq "200") "status=$($recentResp.StatusCode)"
    $recent = $null
    try { $recent = $recentResp.Content | ConvertFrom-Json } catch { $recent = $null }
    $recentCount = if ($recent) { @($recent).Count } else { 0 }
    Assert "最近链路列表非空" ($recentCount -gt 0) "count=$recentCount"
    if ($recentCount -gt 0) {
        $tid = @($recent)[0].traceId
        $qResp = Invoke-LocalHttp "/inner/sw/trace-query?traceId=$tid"
        Assert "按 traceId 查询 HTTP 200" ("$($qResp.StatusCode)" -eq "200") "status=$($qResp.StatusCode)"
        $q = $null
        try { $q = $qResp.Content | ConvertFrom-Json } catch { $q = $null }
        $logCount = if ($q -and $q.logs) { @($q.logs).Count } else { 0 }
        Assert "整条链路 logs > 0" ($logCount -gt 0) "tid=$tid logs=$logCount"
    }

    # ---- 9c. Trace 指标(Phase 5) ----
    Write-Host ""
    Write-Host "---- Trace 指标 /inner/sw/metrics ----"
    $metricsResp = Invoke-LocalHttp "/inner/sw/metrics"
    Assert "指标快照 HTTP 200" ("$($metricsResp.StatusCode)" -eq "200") "status=$($metricsResp.StatusCode)"
    $metricsMetrics = $null
    try { $metricsMetrics = $metricsResp.Content | ConvertFrom-Json } catch { $metricsMetrics = $null }
    if ($metricsMetrics) {
        Assert "metrics.enabled=true" ($metricsMetrics.enabled -eq $true) "enabled=$($metricsMetrics.enabled)"
        Assert "storageEnabled=true" ($metricsMetrics.storageEnabled -eq $true) "storageEnabled=$($metricsMetrics.storageEnabled)"
        $bucketCount = if ($metricsMetrics.buckets) { @($metricsMetrics.buckets).Count } else { 0 }
        Assert "内存窗口桶非空(造数后)" ($bucketCount -gt 0) "buckets=$bucketCount"
        $hasGlobal = $false
        foreach ($b in @($metricsMetrics.buckets)) { if ("$($b.endpoint)" -eq "*") { $hasGlobal = $true } }
        Assert "含全局保留键 *(每桶全局汇总)" $hasGlobal "buckets 中无 endpoint=*"
    } else {
        $script:failures++
        Write-Host "[FAIL] 指标快照返回不可解析的 JSON"
    }

    $qMetricsResp = Invoke-LocalHttp "/inner/sw/metrics/query?endpoint=%2A&limit=1000"
    Assert "指标查询 HTTP 200" ("$($qMetricsResp.StatusCode)" -eq "200") "status=$($qMetricsResp.StatusCode)"
    $qm = $null
    try { $qm = $qMetricsResp.Content | ConvertFrom-Json } catch { $qm = $null }
    if ($qm) {
        Assert "查询分辨率=minute(默认 24h 内)" ("$($qm.resolution)" -eq "minute") "resolution=$($qm.resolution)"
        $qRows = @($qm.rows)
        Assert "全局分钟行非空" ($qRows.Count -gt 0) "count=$($qm.count)"
        $consistent = $true; $bad = ""
        foreach ($r in $qRows) {
            if ([long]$r.errorCount + [long]$r.slowCount -gt [long]$r.requestCount) { $consistent = $false; $bad = "error+slow>request @$($r.timeBucket)" }
            if ([long]$r.sampleCount -gt [long]$r.requestCount) { $consistent = $false; $bad = "sample>request @$($r.timeBucket)" }
            if ($null -ne $r.p50 -and $null -ne $r.p95 -and $null -ne $r.p99) {
                if ([long]$r.p50 -gt [long]$r.p95 -or [long]$r.p95 -gt [long]$r.p99) { $consistent = $false; $bad = "p50>p95>p99 @$($r.timeBucket)" }
            }
        }
        Assert "口径自洽(error+slow<=request, sample<=request, p50<=p95<=p99)" $consistent $bad
    } else {
        $script:failures++
        Write-Host "[FAIL] 指标查询返回不可解析的 JSON"
    }

    # 五档范围路由:1h/6h/24h -> minute, 7d/30d -> hour
    $nowMinute = [long][math]::Floor([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 60000)
    $rangeCases = @(
        @{ name = "1h"; span = 60; expect = "minute" },
        @{ name = "6h"; span = 360; expect = "minute" },
        @{ name = "24h"; span = 1440; expect = "minute" },
        @{ name = "7d"; span = 10080; expect = "hour" },
        @{ name = "30d"; span = 43200; expect = "hour" }
    )
    foreach ($rc in $rangeCases) {
        $fromB = $nowMinute - ($rc.span - 1)
        $rResp = Invoke-LocalHttp "/inner/sw/metrics/query?fromBucket=$fromB&toBucket=$nowMinute&limit=1000"
        $rr = $null; try { $rr = $rResp.Content | ConvertFrom-Json } catch { $rr = $null }
        $resOk = $rr -and ("$($rr.resolution)" -eq "$($rc.expect)")
        Assert "范围 $($rc.name) 路由=$($rc.expect)" (("$($rResp.StatusCode)" -eq "200") -and $resOk) "status=$($rResp.StatusCode) resolution=$($rr.resolution)"
    }

    # limit 截断
    $limResp = Invoke-LocalHttp "/inner/sw/metrics/query?endpoint=%2A&limit=1"
    $lim = $null; try { $lim = $limResp.Content | ConvertFrom-Json } catch { $lim = $null }
    $limCount = if ($lim) { @($lim.rows).Count } else { 0 }
    Assert "limit=1 截断生效" ($lim -and $limCount -le 1) "count=$limCount"

    # ---- 10. 结果 ----
    Write-Host ""
    if ($script:failures -eq 0) {
        Write-Host "==============================================================="
        Write-Host " H2 验证全绿 (exit 0)"
        Write-Host "==============================================================="
    } else {
        Write-Host "==============================================================="
        Write-Host " H2 验证有 $script:failures 项失败 (exit 5)"
        Write-Host "==============================================================="
    }

    if ($KeepRunning) {
        Write-Host ""
        Write-Host "保持运行中(PID=$($script:appProc.Id)),可手动观察:"
        Write-Host "  - 停止: Stop-Process -Id $($script:appProc.Id) -Force"
        Write-Host "  - 对账端点: curl --noproxy * $base/inner/sw/trace-parity"
        Write-Host "  - 统计端点: curl --noproxy * $base/statistic"
        if ($ConsoleEnabled) {
            Write-Host "  - H2 Console: http://127.0.0.1:$ConsolePort"
        }
        Write-Host "  - agent 日志: $agentDir\logs\skywalking-api.log"
        Wait-Process -Id $script:appProc.Id
        exit 0
    } else {
        Stop-App
        if ($script:failures -gt 0) { exit 5 }
        exit 0
    }

} catch {
    Write-Host "[FAIL] 未捕获异常: $_"
    Stop-App
    exit 1
}
