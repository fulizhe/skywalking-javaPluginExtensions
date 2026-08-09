# 02 运行底座:构建插件 -> 装入 agent -> 启动演示应用 -> 验证插件加载(手动模式)
#
# 一条命令让"在真实 agent 下跑起来"成为可能。默认行为:
#   1) 若 demo-app jar 缺失,先构建应用(独立 pom,mvn -f)
#   2) 复用现有 maven 精确选择构建编译 logfile-reporter-plugin 并拷贝到 agent plugins
#   3) 以 -javaagent + -Dskywalking.* 参数启动演示应用(WebPort=<Port>),轮询端口就绪
#   4) 清空 agent 日志后启动,验证 skywalking-api.log 出现 "logfile-reporter-plugin-*.jar loaded"
#   5) 手动模式:验证通过后保持运行,可手动观察(日志、探针、仪表盘)
#
# 用法(建议用 PowerShell 7 / pwsh 运行,避免 Windows PowerShell 5.1 控制台编码问题):
#   pwsh ./scripts/run-with-agent.ps1                  # 全流程,端口 9600
#   pwsh ./scripts/run-with-agent.ps1 -Port 9601       # 换端口
#   pwsh ./scripts/run-with-agent.ps1 -SkipPluginBuild # 插件已构建过,跳过 maven
#   pwsh ./scripts/run-with-agent.ps1 -AgentDir D:\apps\apache-skywalking-java-agent-9.4.0
#
# 退出码:0 全流程通过;1 前置失败(路径/构建/拷贝);2 应用未就绪;3 插件加载未验证
param(
    [string]$AgentDir = "",
    [string]$JavaHome = "",
    [switch]$SkipPluginBuild,
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
    # 仓库约定 JDK 8 为演示运行环境(见 logfile-reporter-plugin/README-compile.md),优先于全局 JAVA_HOME
    $jdk8Candidates = Get-ChildItem "$(Get-DriveRoot)\apps\java" -Directory -Filter "jdk1.8*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk8Candidates) { return $jdk8Candidates.FullName }
    if ($env:JAVA_HOME) { return $env:JAVA_HOME }
    throw "未找到 JDK。请用 -JavaHome 指定,或设置 JAVA_HOME。"
}

function Test-Ready($url, $seconds) {
    for ($i = 0; $i -lt $seconds; $i++) {
        Start-Sleep -Seconds 1
        try {
            $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2
            if ($r.StatusCode -eq 200) { return $true }
        } catch { }
    }
    return $false
}

# ---- 1. 应用 jar(缺失则构建)----
if (-not (Test-Path $appJar)) {
    Write-Host "[..] demo-app jar 缺失,开始构建: mvn -f $demoAppDir\pom.xml clean package -DskipTests"
    Push-Location $demoAppDir
    mvn -f "$demoAppDir\pom.xml" clean package -DskipTests -q
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { Write-Host "[FAIL] demo-app 构建失败 (exit=$code)"; exit 1 }
}
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

# ---- 3. JDK 8 ----
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
    # 与 README-compile.md 一致:构建环境用 JDK 8(前置到 PATH)
    $env:JAVA_HOME = $javaHome
    $env:PATH = "$javaHome\bin;$env:PATH"
    Write-Host "[..] 构建插件: mvn clean package -Dmaven.test.skip=true -T 2C -pl logfile-reporter-plugin -am (JDK8)"
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
    Write-Host "[FAIL] 应用 90 秒内未就绪。stderr 尾部:"
    Get-Content $errLog -ErrorAction SilentlyContinue | Select-Object -Last 15
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
