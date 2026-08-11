# 02 运行底座:构建插件 -> 装入 agent -> 启动演示应用 -> 验证插件加载(手动模式)
#
# 一条命令让"在真实 agent 下跑起来"成为可能。默认行为:
#   1) 始终构建 demo-app(mvn -f,保证源码变更必然生效;快速路径用 -SkipAppBuild 复用已有 jar)
#   2) 复用现有 maven 精确选择构建编译 logfile-reporter-plugin 并拷贝到 agent plugins
#   3) 以 -javaagent + -Dskywalking.* 参数启动演示应用(WebPort=<Port>),轮询端口就绪
#   4) 清空 agent 日志后启动,验证 skywalking-api.log 出现 "logfile-reporter-plugin-*.jar loaded"
#   5) 手动模式:验证通过后保持运行,可手动观察(日志、探针、仪表盘)
#
# 用法(建议用 PowerShell 7 / pwsh 运行,避免 Windows PowerShell 5.1 控制台编码问题):
#   pwsh ./scripts/run-with-agent.ps1                  # 全流程,端口 9600(演示运行时默认 JDK 8)
#   pwsh ./scripts/run-with-agent.ps1 -Port 9601       # 换端口
#   pwsh ./scripts/run-with-agent.ps1 -SkipPluginBuild # 插件已构建过,跳过 maven
#   pwsh ./scripts/run-with-agent.ps1 -SkipAppBuild    # 复用已有 demo-app jar,跳过重建
#   pwsh ./scripts/run-with-agent.ps1 -AgentDir D:\apps\apache-skywalking-java-agent-9.4.0
#   pwsh ./scripts/run-with-agent.ps1 -JavaHome D:\apps\java\jdk-17.0.8   # 演示运行时用 JDK 17
#   pwsh ./scripts/run-with-agent.ps1 -BuildJavaHome D:\apps\java\jdk-17.0.8  # 构建工具链用 JDK 17
#
# 退出码:0 全流程通过;1 前置失败(路径/构建/拷贝);2 应用未就绪;3 插件加载未验证
param(
    [string]$AgentDir = "",
    [string]$JavaHome = "",
    [string]$BuildJavaHome = "",
    [switch]$SkipPluginBuild,
    [switch]$SkipAppBuild,
    [int]$Port = 9600
)

$ErrorActionPreference = "Stop"

$demoAppDir = Split-Path -Parent $PSScriptRoot          # agent/demo-app
$agentModuleDir = Split-Path -Parent $demoAppDir        # agent
$repoRoot = Split-Path -Parent $agentModuleDir          # 仓库根
$pluginJarName = "logfile-reporter-plugin-1.0.0.jar"
$appJar = Join-Path $demoAppDir "target\demo-app-1.0.0.jar"
$pluginJar = Join-Path $agentModuleDir "logfile-reporter-plugin\target\$pluginJarName"

function Get-DriveRoot {
    if (Test-Path 'E:\') { return 'E:' }
    return 'D:'
}

function Resolve-AgentDir {
    if ($AgentDir) { return $AgentDir }
    if ($env:SKYWALKING_AGENT_DIR) { return $env:SKYWALKING_AGENT_DIR }
    return "$(Get-DriveRoot)\apps\apache-skywalking-java-agent-9.4.0"
}

function Resolve-JavaHome {
    if ($JavaHome) { return $JavaHome }
    # 演示运行时约定 JDK 8 为默认(见 logfile-reporter-plugin/README-compile.md),JDK 17 用 -JavaHome 显式指定;
    # 若本机无 JDK 8 则回落到全局 JAVA_HOME
    $jdk8Candidates = Get-ChildItem "$(Get-DriveRoot)\apps\java" -Directory -Filter "jdk1.8*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk8Candidates) { return $jdk8Candidates.FullName }
    if ($env:JAVA_HOME) { return $env:JAVA_HOME }
    throw "未找到 JDK。请用 -JavaHome 指定,或设置 JAVA_HOME。"
}

function Resolve-BuildJavaHome {
    # 构建工具链约定 JDK 17:插件产物经 release 8 保持字节码基线 8,需 JDK 9+ 编译器(见 adr-01);
    # 显式 -BuildJavaHome 优先,其次扫描本机 jdk-17*,最后兜底演示运行时 JDK
    if ($BuildJavaHome) { return $BuildJavaHome }
    $jdk17Candidates = Get-ChildItem "$(Get-DriveRoot)\apps\java" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^jdk[-_.]?17' } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk17Candidates) { return $jdk17Candidates.FullName }
    return $javaHome
}

function Test-Release8Capable([string]$jdkHome) {
    # release 8 需 JDK 9+ 编译器;JDK 8 工具链会在 javac 报不透明的 --release 错误,提前给可读提示
    $verLine = & (Join-Path $jdkHome "bin\java.exe") -version 2>&1 | Select-Object -First 1
    return ($verLine -match '"(\d+)' -and [int]$Matches[1] -ge 9)
}

# 就绪探测:优先 curl.exe(Windows 10 1803+ 自带),回环地址 --noproxy "*" 直连,
# 不受 .NET/IE 代理与 Profile 默认参数覆盖影响;超时放宽以容纳冷启动首请求。
function Test-Ready($url, $seconds) {
    for ($i = 0; $i -lt $seconds; $i++) {
        Start-Sleep -Seconds 1
        if (Test-HttpOnce $url) { return $true }
    }
    return $false
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

# ---- 1. 应用 jar(默认每次重建,保证源码变更必然生效)----
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

# ---- 2. agent 目录 ----
$agentDir = Resolve-AgentDir
$agentJar = Join-Path $agentDir "skywalking-agent.jar"
if (-not (Test-Path $agentJar)) {
    Write-Host "[FAIL] agent 目录无效(缺 skywalking-agent.jar): $agentDir"
    Write-Host "       可用 -AgentDir 或环境变量 SKYWALKING_AGENT_DIR 指定。"
    exit 1
}
Write-Host "[OK] agent: $agentDir"

# ---- 3. 演示运行时 JDK(默认 JDK 8,-JavaHome 显式 JDK 17)----
$javaHome = Resolve-JavaHome
$javaExe = Join-Path $javaHome "bin\java.exe"
if (-not (Test-Path $javaExe)) {
    Write-Host "[FAIL] 无效的 JDK 目录: $javaHome"
    exit 1
}
Write-Host "[OK] JDK: $javaHome"

# ---- 4. 端口占用预检 ----
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    Write-Host "[FAIL] 端口 $Port 已被占用。请先停止旧实例(任务管理器/Stop-Process),或换 -Port。"
    exit 1
}

# ---- 5. 构建插件并拷贝 ----
if (-not $SkipPluginBuild) {
    # 构建工具链用 JDK 17(release 8,产物字节码 8;见 adr-01),默认扫描本机 jdk-17*,可用 -BuildJavaHome 覆盖
    $buildJavaHome = Resolve-BuildJavaHome
    if (-not (Test-Release8Capable $buildJavaHome)) {
        Write-Host "[FAIL] 插件构建工具链需 JDK 9+($buildJavaHome 不支持 release 8)。请用 -BuildJavaHome 指定 JDK 17。"
        exit 1
    }
    $env:JAVA_HOME = $buildJavaHome
    $env:PATH = "$buildJavaHome\bin;$env:PATH"
    Write-Host "[..] 构建插件: mvn clean package -Dmaven.test.skip=true -T 2C -pl logfile-reporter-plugin -am (toolchain=$buildJavaHome)"
    Push-Location $agentModuleDir
    mvn clean package "-Dmaven.test.skip=true" -T 2C -pl logfile-reporter-plugin -am -q
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { Write-Host "[FAIL] 插件构建失败 (exit=$code)"; exit 1 }
}
if (-not (Test-Path $pluginJar)) {
    Write-Host "[FAIL] 插件 jar 不存在: $pluginJar(请先构建或去掉 -SkipPluginBuild)"
    exit 1
}
$agentPluginJar = Join-Path $agentDir "plugins\$pluginJarName"
Copy-Item $pluginJar $agentPluginJar -Force
if (-not (Test-Path $agentPluginJar)) { Write-Host "[FAIL] 插件 jar 拷贝失败"; exit 1 }
Write-Host "[OK] 插件已装入: $agentPluginJar"

# ---- 6. 清空 agent 运行时日志(避免把历史运行误判为本次加载)----
Remove-Item (Join-Path $agentDir "logs\skywalking-api.log") -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $agentDir "logs\skywalking-agent.log") -Force -ErrorAction SilentlyContinue

# ---- 7. 启动演示应用 ----
# 告警参数与 src/main/resources/agent.trace-alert.config.sample 对齐;
# 规则必须带 METHOD 前缀(Spring MVC 端点 operation 名恒带 GET:/POST: 前缀,省略则豁免/阈值不生效)
$swArgs = @(
    "-javaagent:$agentJar",
    "-Dskywalking.agent.keep_tracing=true",
    "-Dskywalking.plugin.logfilereporter.alert.enabled=true",
    "-Dskywalking.plugin.logfilereporter.alert.slow_rules=operation:GET:/status/*=8000;operation:GET:/api/order/*=8000;operation:GET:/api/export/**=60000",
    "-Dskywalking.plugin.logfilereporter.alert.error_ignore_rules=operation:GET:/.well-known/**=404;operation:GET:/status/*=503,500,400;operation:GET:/api/exists/*=404;operation:GET:/inner/business-test/**=404,410"
)
$env:WebPort = "$Port"
$outLog = Join-Path $demoAppDir "target\run-with-agent-out.log"
$errLog = Join-Path $demoAppDir "target\run-with-agent-err.log"
Write-Host "[..] 启动 demo-app (javaagent, WebPort=$Port): http://127.0.0.1:$Port/"
$proc = Start-Process -FilePath $javaExe -ArgumentList ($swArgs + @("-jar", $appJar)) `
    -WorkingDirectory $demoAppDir -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
Write-Host "[OK] demo-app PID=$($proc.Id), 日志: $outLog / $errLog"

# ---- 8. 就绪检查 ----
if (-not (Test-Ready "http://127.0.0.1:$Port/" 90)) {
    Write-Host "[FAIL] 应用 90 秒内未就绪。"
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        Write-Host "       [诊断] TCP $Port 正在监听(应用进程存活),但探测未拿到 200——请确认浏览器可访问 http://127.0.0.1:$Port/。"
    } else {
        Write-Host "       [诊断] TCP $Port 无监听。stderr 尾部:"
        Get-Content $errLog -ErrorAction SilentlyContinue | Select-Object -Last 15
    }
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    exit 2
}
Write-Host "[OK] 应用就绪"

# ---- 9. 插件加载验证(agent 日志)----
$apiLog = Join-Path $agentDir "logs\skywalking-api.log"
Start-Sleep -Seconds 2
$loaded = Select-String -Path $apiLog -Pattern "logfile-reporter-plugin-[\d.]+\.jar loaded" -ErrorAction SilentlyContinue
if (-not $loaded) {
    Write-Host "[FAIL] agent 日志未出现插件加载记录: $apiLog"
    Get-Content $apiLog -ErrorAction SilentlyContinue | Select-Object -Last 10
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    exit 3
}
Write-Host "[OK] 插件加载已验证: $($loaded[0].Line.Trim())"

# ---- 10. 手动模式:保持运行 ----
Write-Host ""
Write-Host "验证通过。保持运行中(PID=$($proc.Id))。"
Write-Host "  - 停止:按 Ctrl+C 或 Stop-Process -Id $($proc.Id) -Force"
Write-Host "  - agent 日志: $agentDir\logs\skywalking-agent.log / skywalking-api.log"
Write-Host "  - 应用日志: $outLog"
Wait-Process -Id $proc.Id
Write-Host "[OK] demo-app 已退出。"
exit 0
