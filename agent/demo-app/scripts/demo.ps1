# demo 一条命令跑起来:中间件 + 应用 + 造数 + 开页面(压测再加个开关)
#
# 设计目标:**日常验证只需要两条命令,不做任何前置**(不设环境变量、不手动拉镜像、
# 不手动点页面)。端口等前置条件都固化进脚本与 application.yml 了。
#
#   pwsh .\scripts\demo.ps1              # ① 起中间件 + 起应用 + 造数 + 打开依赖拓扑页
#   pwsh .\scripts\demo.ps1 -Stress      # ② 同上,再压测一2000 次 / 16 线程
#   pwsh .\scripts\demo.ps1 -Stop        # 收工:停应用 + 停中间件
#
# 为什么不用 compose 起应用:本机调试要"改完代码立刻生效"(每次重建 demo-app jar),
# run-with-agent.ps1 已经把这条路径做熟了;compose 那条留给演示与远程(C 场景),
# 见 README-verify-matrix.md。
param(
    # 起应用并造数后,顺便压测一压
    [switch]$Stress,
    # 压测参数(给了 -Stress 才会用)
    [int]$Requests = 2000,
    [int]$Threads = 16,
    # 无限模式:一直压到 Ctrl+C
    [switch]$Continuous,
    # 压测档位:全部页面接口 / 只业务正常端点 / 只依赖三层
    [ValidateSet("AllEndpoints", "NormalOnly", "WithDeps")]
    [string]$Mode = "AllEndpoints",
    # 收工:停应用 + 停中间件容器
    [switch]$Stop,
    # 不碰中间件(只用应用自身的依赖:进程内 H2 与回环 Hutool HTTP)
    [switch]$NoDeps,
    # 应用端口
    [int]$Port = 9600,
    # 跳过 demo-app 重新构建(复用已有 jar)
    [switch]$SkipAppBuild,
    # 跳过插件构建
    [switch]$SkipPluginBuild,
    [string]$AgentDir = "",
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"

$demoAppDir = Split-Path -Parent $PSScriptRoot          # agent/demo-app
$agentModuleDir = Split-Path -Parent $demoAppDir        # agent
$repoRoot = Split-Path -Parent $agentModuleDir          # 仓库根
$composeFile = Join-Path $demoAppDir "docker-compose.yaml"
$appJar = Join-Path $demoAppDir "target\demo-app-1.0.0.jar"
$appLog = Join-Path $env:TEMP "demo-app.log"
$base = "http://127.0.0.1:$Port"
$script:appProc = $null

function Get-DriveRoot { return 'D:' }

function Resolve-AgentDir {
    if ($AgentDir) { return $AgentDir }
    if ($env:SKYWALKING_AGENT_DIR) { return $env:SKYWALKING_AGENT_DIR }
    return "$(Get-DriveRoot)\apps\apache-skywalking-java-agent-9.4.0"
}

function Resolve-JavaHome {
    if ($JavaHome) { return $JavaHome }
    $jdk8 = Get-ChildItem "$(Get-DriveRoot)\apps\java" -Directory -Filter "jdk1.8*" -EA SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk8) { return $jdk8.FullName }
    if ($env:JAVA_HOME) { return $env:JAVA_HOME }
    throw "未找到 JDK 8。请用 -JavaHome 指定,或设置 JAVA_HOME。"
}

function Test-HttpOnce($url) {
    if (Get-Command curl.exe -EA SilentlyContinue) {
        $code = & curl.exe -s -o NUL -w "%{http_code}" --noproxy "*" --connect-timeout 3 --max-time 5 $url 2>$null
        return "$code" -eq "200"
    }
    try { return (Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200 } catch { return $false }
}

function Wait-Ready($url, $seconds) {
    for ($i = 0; $i -lt $seconds; $i++) {
        if (Test-HttpOnce $url) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Invoke-Curl($path) {
    & curl.exe -s -o NUL --noproxy "*" --max-time 20 "$base$path" 2>$null
}

# ---------------------------------------------------------------- 中间件

function Start-Deps {
    Write-Host "[1/4] 中间件(redis / mysql / kafka)"
    if ($NoDeps) {
        Write-Host "      -NoDeps:跳过。依赖图里 Redis / MySQL 两层会没有节点(不是红边)。"
        return
    }
    if (-not (Get-Command docker -EA SilentlyContinue)) {
        Write-Host "      没有 docker:跳过。Redis / MySQL 两层不会有节点,其余照常。"
        return
    }
    Push-Location $demoAppDir
    try {
        & docker compose --profile deps up -d redis mysql kafka 2>&1 | Out-Null
    } finally {
        Pop-Location
    }
    $running = @(& docker ps --filter "name=demo-app-stability" --format "{{.Names}}" 2>$null)
    $up = @($running | Where-Object { $_ -match "redis|mysql|kafka" }).Count
    if ($up -gt 0) {
        Write-Host "      已在跑:$up/3 个(健康检查通过才会计入)"
    } else {
        Write-Host "      没起来 —— 也能继续,只是 Cache / Database 两层没有依赖边。"
    }
}

# ---------------------------------------------------------------- 应用

function Start-App {
    Write-Host "[2/4] 应用(带 agent,端口 $Port)"
    $agentDir = Resolve-AgentDir
    $javaHome = Resolve-JavaHome
    if (-not (Test-Path $agentDir)) {
        Write-Host "      [WARN] agent 目录不存在:$agentDir(依赖面/告警会缺,其余页面仍在)"
    }
    if (-not $SkipAppBuild) {
        Write-Host "      构建 demo-app(mvn -q package -DskipTests)"
        Push-Location $demoAppDir
        try { mvn -q -B -ntp package -DskipTests } finally { Pop-Location }
    }
    if (-not (Test-Path $appJar)) {
        Write-Host "      [FAIL] 没有 jar:$appJar(去掉 -SkipAppBuild 重试)"
        exit 1
    }
    $java = Join-Path $javaHome "bin\java.exe"
    $a = @(
        "-javaagent:$agentDir\skywalking-agent.jar",
        "-Dskywalking.agent.keep_tracing=true",
        "-Dskywalking.plugin.logfilereporter.metrics.enabled=true",
        "-Dskywalking.plugin.logfilereporter.h2.enabled=true",
        "-Dskywalking.plugin.logfilereporter.h2.console_port=8092",
        "-Dskywalking.plugin.logfilereporter.alert.enabled=false",
        "-jar", $appJar
    )
    # 后台起,日志落文件:本脚本要在起完之后继续造数、开页面
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $java
    $psi.Arguments = ($a -join " ")
    $psi.WorkingDirectory = $demoAppDir
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $script:appProc = New-Object System.Diagnostics.Process
    $script:appProc.StartInfo = $psi
    $script:appProc.Start() | Out-Null
    $script:appProc.BeginOutputReadLine()
    $script:appProc.BeginErrorReadLine()

    if (-not (Wait-Ready "$base/" 90)) {
        Write-Host "      [FAIL] 应用 90s 内没就绪。日志: agent\demo-app\target\ 下 mvn 输出,或看 $appLog"
        exit 2
    }
    Write-Host "      就绪(pid $($script:appProc.Id))"
}

# ---------------------------------------------------------------- 造数

function Seed-Data {
    Write-Host "[3/4] 造数"
    # 全貌入口:每种被监控的组件各打一次(含三层依赖 + 外呼)
    Invoke-Curl "/fullSample" | Out-Null
    # 分层各打一点,让拓扑图右列节点齐
    Invoke-Curl "/api/deps-demo/all" | Out-Null
    Invoke-Curl "/api/deps-demo/kafka?op=consume" | Out-Null
    # 25 轮业务端点:越过"单边样本 ≥20 才出 p90/p95"的门槛,分位才有值
    for ($i = 0; $i -lt 25; $i++) {
        Invoke-Curl "/queryDbByMybatis" | Out-Null
        Invoke-Curl "/queryDbByJdbc" | Out-Null
        Invoke-Curl "/api/hutool-demo/post-json?value=t&phase=p" | Out-Null
    }
    Write-Host "      完成。注意:依赖边是**分钟级窗口**,接下来 4 分钟内去看图。"
}

function Open-Pages {
    Write-Host "[4/4] 打开页面"
    Start-Process "http://127.0.0.1:$Port/dashboards/topology.html" 2>$null | Out-Null
    Write-Host "      依赖拓扑:http://127.0.0.1:$Port/dashboards/topology.html"
    Write-Host "      导览首页:http://127.0.0.1:$Port/"
}

# ---------------------------------------------------------------- 压测 / 收工

function Invoke-Stress {
    Write-Host ""
    Write-Host "==== 压测($Mode,$Requests 次 / $Threads 线程)===="
    $args = @("-NoProfile", "-File", (Join-Path $PSScriptRoot "stress.ps1"),
        "-BaseUrl", $base, "-Requests", $Requests, "-Threads", $Threads, "-Mode" , $Mode)
    if ($Continuous) { $args += "-Continuous" }
    & pwsh @args
}

function Stop-All {
    Write-Host "[收工] 停应用"
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -EA SilentlyContinue |
        Where-Object { $_.CommandLine -match "demo-app-1\.0\.0\.jar" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -EA SilentlyContinue }
    if (-not $NoDeps -and (Get-Command docker -EA SilentlyContinue)) {
        Write-Host "[收工] 停中间件"
        Push-Location $demoAppDir
        try { & docker compose --profile deps stop redis mysql kafka 2>&1 | Out-Null } finally { Pop-Location }
        Write-Host "        (只 stop 不 down,数据卷留着,下次起更快)"
    }
    Write-Host "完成。"
}

# ---------------------------------------------------------------- 主流程

if ($Stop) { Stop-All; exit 0 }

Start-Deps
Start-App
Seed-Data
Open-Pages

if ($Stress) { Invoke-Stress }
else {
    Write-Host ""
    Write-Host "下一步:压测就加 -Stress(例如 -Stress -Mode WithDeps);收工用 -Stop。"
}