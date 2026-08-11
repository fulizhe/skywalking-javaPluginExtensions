# 08 setup 脚本:环境自足 — 检测/下载 agent 9.4.0 -> 构建并安装插件 -> 产出启动命令
#
# 不依赖作者机器:在任意 Windows 机器上跑一次即可把环境备齐。流程:
#   1) 检测本机 skywalking-java-agent 9.4.0:存在(目录含 skywalking-agent.jar)则跳过;
#      缺失则从 Apache 官方发行包下载(.tgz,校验 SHA512,解压到目标目录)
#   2) 构建 logfile-reporter-plugin(JDK 17 工具链 + Maven,产物字节码 8)并拷贝进 agent plugins/
#   3) 构建 demo-app jar(默认每次重建,保证源码变更必然生效;-SkipAppBuild 快速路径)
#   4) 产出与 scripts/run-with-agent.ps1 同构的启动命令
#
# 幂等:重复执行不产生副作用——agent 已存在则跳过下载,插件/应用构建结果以 -Force 拷贝覆盖。
#
# 用法(建议用 PowerShell 7 / pwsh 运行,避免 Windows PowerShell 5.1 控制台编码问题):
#   pwsh ./scripts/setup.ps1                            # 全流程,默认装到 D:\apps\apache-skywalking-java-agent-9.4.0
#   pwsh ./scripts/setup.ps1 -AgentDir D:\apps\agent-9.4.0
#   pwsh ./scripts/setup.ps1 -SkipPluginBuild           # 插件已构建过,只保证安装与出命令
#
# 退出码:0 全流程通过;1 前置失败(下载/解压/构建/安装)
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

$AgentVersion = "9.4.0"
$AgentTgzName = "apache-skywalking-java-agent-$AgentVersion.tgz"
$AgentUrl = "https://archive.apache.org/dist/skywalking/java-agent/$AgentVersion/$AgentTgzName"
$AgentShaUrl = "$AgentUrl.sha512"

function Get-DriveRoot {
    if (Test-Path 'E:\') { return 'E:' }
    return 'D:'
}

function Resolve-AgentDir {
    if ($AgentDir) { return $AgentDir }
    if ($env:SKYWALKING_AGENT_DIR) { return $env:SKYWALKING_AGENT_DIR }
    return "$(Get-DriveRoot)\apps\apache-skywalking-java-agent-$AgentVersion"
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

# 下载:优先 curl.exe(带 -L/--fail/重试);失败兜底 Invoke-WebRequest(走系统/IE 代理)。
# 注意 curl.exe 默认不读系统代理,若机器必须经代理访问外网而环境变量未设,会走兜底。
function Invoke-Download($url, $dest) {
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
        & curl.exe -L --fail --retry 2 --connect-timeout 15 -o $dest $url 2>$null
        return (($LASTEXITCODE -eq 0) -and (Test-Path $dest))
    }
    try {
        Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing -TimeoutSec 300
        return (Test-Path $dest)
    } catch { return $false }
}

function Test-Sha512($file, $shaUrl) {
    $shaTmp = "$file.sha512"
    if (-not (Invoke-Download $shaUrl $shaTmp)) {
        Write-Host "[!] 校验文件下载失败,跳过 SHA512 校验"
        return $true
    }
    $expected = ((Get-Content $shaTmp -Raw) -split '\s+' | Where-Object { $_ } | Select-Object -First 1).ToLowerInvariant()
    $actual = (Get-FileHash -Path $file -Algorithm SHA512).Hash.ToLowerInvariant()
    return $expected -eq $actual
}

function Install-Agent {
    $parent = Split-Path $agentDir -Parent
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    if (-not (Get-Command tar.exe -ErrorAction SilentlyContinue)) {
        throw "未找到 tar.exe(Windows 10 1803+ 自带),无法解压发行包。请安装后重试。"
    }
    $staging = Join-Path $env:TEMP ("skywalking-agent-setup-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $staging -Force | Out-Null
    $tgz = Join-Path $staging $AgentTgzName
    try {
        Write-Host "[..] 下载发行包(约 33MB): $AgentUrl"
        if (-not (Invoke-Download $AgentUrl $tgz)) {
            throw "发行包下载失败。请检查网络/代理,或手动下载 $AgentUrl 并解压 skywalking-agent 到 $agentDir。"
        }
        Write-Host "[OK] 下载完成:$([math]::Round((Get-Item $tgz).Length / 1MB, 1))MB"
        if (-not (Test-Sha512 $tgz $AgentShaUrl)) {
            throw "SHA512 校验失败(文件损坏或被篡改),已中止。"
        }
        Write-Host "[OK] SHA512 校验通过"
        Write-Host "[..] 解压: tar -xzf $AgentTgzName"
        Push-Location $staging
        tar -xzf $tgz
        $code = $LASTEXITCODE
        Pop-Location
        if ($code -ne 0) { throw "解压失败 (exit=$code)" }
        $extracted = Join-Path $staging "skywalking-agent"
        if (-not (Test-Path (Join-Path $extracted "skywalking-agent.jar"))) {
            throw "解压后未找到 skywalking-agent.jar: $extracted"
        }
        if (Test-Path $agentDir) {
            Write-Host "[!] 目标目录存在但缺 skywalking-agent.jar,移除残留后覆盖: $agentDir"
            Remove-Item $agentDir -Recurse -Force
        }
        Move-Item $extracted $agentDir
        Write-Host "[OK] agent 已安装: $agentDir"
    } finally {
        Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "==============================================================="
Write-Host " demo-app 环境自足 setup (ticket 08)"
Write-Host "   agentVersion=$AgentVersion  port=$Port  skipPluginBuild=$SkipPluginBuild"
Write-Host "==============================================================="

# ---- 1. agent:检测或下载安装 ----
$agentDir = Resolve-AgentDir
$agentJar = Join-Path $agentDir "skywalking-agent.jar"
if (Test-Path $agentJar) {
    Write-Host "[OK] 已检测到 agent: $agentDir(跳过下载)"
} else {
    Write-Host "[..] 未检测到 agent($AgentVersion),开始安装到: $agentDir"
    Install-Agent
    if (-not (Test-Path $agentJar)) { Write-Host "[FAIL] agent 安装未生效: $agentDir"; exit 1 }
}

# ---- 2. JDK + Maven(演示运行时默认 JDK 8;构建工具链默认 JDK 17,见 Resolve-BuildJavaHome)----
$javaHome = Resolve-JavaHome
$javaExe = Join-Path $javaHome "bin\java.exe"
if (-not (Test-Path $javaExe)) { Write-Host "[FAIL] 无效的 JDK 目录: $javaHome"; exit 1 }
Write-Host "[OK] JDK: $javaHome"
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host "[FAIL] 未找到 Maven(mvn)。请安装 Maven 并加入 PATH。"
    exit 1
}
Write-Host "[OK] Maven: $((Get-Command mvn).Source)"

# ---- 3. 构建并安装插件 ----
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

# ---- 4. demo-app jar(默认每次重建,保证源码变更必然生效;快速路径 -SkipAppBuild)----
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

# ---- 5. 产出启动命令(与 scripts/run-with-agent.ps1 同构)----
# 告警参数与 src/main/resources/agent.trace-alert.config.sample 对齐;
# 规则必须带 METHOD 前缀(Spring MVC 端点 operation 名恒带 GET:/POST: 前缀,省略则豁免/阈值不生效)
$swArgs = @(
    "-javaagent:$agentJar",
    "-Dskywalking.agent.keep_tracing=true",
    "-Dskywalking.plugin.logfilereporter.alert.enabled=true",
    "-Dskywalking.plugin.logfilereporter.alert.slow_rules=operation:GET:/status/*=8000;operation:GET:/api/order/*=8000;operation:GET:/api/export/**=60000",
    "-Dskywalking.plugin.logfilereporter.alert.error_ignore_rules=operation:GET:/.well-known/**=404;operation:GET:/status/*=503,500,400;operation:GET:/api/exists/*=404;operation:GET:/inner/business-test/**=404,410"
)
Write-Host ""
Write-Host "================ 启动命令 ================"
Write-Host "一键验证回路(推荐):"
Write-Host "  pwsh $demoAppDir\scripts\validate.ps1"
Write-Host ""
Write-Host "手动调试(保持运行,浏览器打开 http://127.0.0.1:$Port/):"
Write-Host "  pwsh $demoAppDir\scripts\run-with-agent.ps1 -SkipPluginBuild"
Write-Host ""
Write-Host "直接启动(与运行底座脚本同参,可复制到终端执行):"
Write-Host "  `$env:WebPort = `"$Port`""
Write-Host "  & `"$javaExe`" $(($swArgs + @("-jar", $appJar)) -join ' ')"
Write-Host ""
Write-Host "[OK] 环境就绪。请执行上面的启动命令开始演示。"
exit 0
