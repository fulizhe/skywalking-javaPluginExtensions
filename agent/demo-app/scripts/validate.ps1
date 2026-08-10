# 05 验证回路:一条命令完成 构建插件 -> 安装进 agent -> 启动 -> 造数 -> 断言 -> 报告
#
# 断言范围(核心三件,见 spec User Stories 1-6):
#   A. trace 缓存合并  :同一 traceId 下入口+出口两个 segment 合并在一个条目,字段齐全
#   B. 告警链(端到端) :慢(默认阈值+Ant 规则)/错(isError+HTTP 500)命中 -> webhook 收讫计数;
#                      豁免规则(ignore-rule)命中 -> 无事件;对照请求 -> 无事件
#   C. 运行时开关      :disable 后统计快照停止增长,enable 后恢复
#   附:插件加载验证(agent 日志)、五类数据流读口非提示(03/04 验收点的冒烟覆盖)
#
# 用法(建议用 PowerShell 7 / pwsh 运行,避免 Windows PowerShell 5.1 控制台编码问题):
#   pwsh ./scripts/validate.ps1                     # 全流程(构建插件 -> 安装 -> 启动 -> 验证),端口 9600
#   pwsh ./scripts/validate.ps1 -SkipPluginBuild    # 插件已构建,跳过 maven 直接安装启动
#   pwsh ./scripts/validate.ps1 -Port 9601 -AgentDir D:\apps\apache-skywalking-java-agent-9.4.0
#
# 负向测试(验证"插件未安装时大声失败"):
#   pwsh ./scripts/validate.ps1 -SkipPluginInstall  # 故意不安装插件 jar,预期在插件加载检查处失败(exit 3)
#
# IDE 手动模式(与脚本模式同参,共享 -AgentDir/-JavaHome/-SkipPluginBuild/-Port):
#   pwsh ./scripts/run-with-agent.ps1 -SkipPluginBuild
#
# 退出码:0 全绿;1 前置失败(路径/构建/安装);2 应用未就绪;3 插件未安装/未加载;5 断言失败
param(
    [string]$AgentDir = "",
    [string]$JavaHome = "",
    [switch]$SkipPluginBuild,
    [switch]$SkipAppBuild,
    [switch]$SkipPluginInstall,
    [int]$Port = 9600
)

$ErrorActionPreference = "Stop"

$demoAppDir = Split-Path -Parent $PSScriptRoot          # agent/demo-app
$agentModuleDir = Split-Path -Parent $demoAppDir        # agent
$repoRoot = Split-Path -Parent $agentModuleDir          # 仓库根
$pluginJarName = "logfile-reporter-plugin-1.0.0.jar"
$appJar = Join-Path $demoAppDir "target\demo-app-1.0.0.jar"
$pluginJar = Join-Path $agentModuleDir "logfile-reporter-plugin\target\$pluginJarName"
$outLog = Join-Path $demoAppDir "target\validate-out.log"
$errLog = Join-Path $demoAppDir "target\validate-err.log"
$base = "http://127.0.0.1:$Port"
$script:appProc = $null
$script:failures = 0

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
    $jdk8Candidates = Get-ChildItem "$(Get-DriveRoot)\apps\java" -Directory -Filter "jdk1.8*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk8Candidates) { return $jdk8Candidates.FullName }
    if ($env:JAVA_HOME) { return $env:JAVA_HOME }
    throw "未找到 JDK。请用 -JavaHome 指定,或设置 JAVA_HOME。"
}

function Assert($name, $cond, $detail) {
    if ($cond) { Write-Host "[PASS] $name" }
    else { $script:failures++; Write-Host "[FAIL] $name`n       $detail" }
}

# 本地 HTTP 统一走 curl.exe --noproxy "*":交互式 profile 可能给 Invoke-WebRequest/RestMethod
# 注入默认 Proxy 参数,把回环请求误送外部代理 -> 代理够不到本机 127.0.0.1,回 502(Bad Gateway),
# 应用端日志无任何到达记录。curl 直连可彻底绕开(见 ticket 05 实测)。
function Invoke-LocalHttp($path, [string]$Method = "GET") {
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
        $args = @("-s", "-o", "-", "--noproxy", "*", "-X", $Method, "--max-time", "30",
            "-w", "`n%{http_code}")
        $raw = @(& curl.exe @args "$base$path" 2>$null)
        $code = $raw[-1]
        $body = if ($raw.Count -gt 1) { ($raw[0..($raw.Count - 2)] -join "`n") } else { "" }
        return @{ StatusCode = "$code"; Content = $body }
    }
    $r = Invoke-WebRequest "$base$path" -Method $Method -UseBasicParsing -TimeoutSec 30
    return @{ StatusCode = "$($r.StatusCode)"; Content = "$($r.Content)" }
}

function Get-Json($path) {
    $r = Invoke-LocalHttp $path
    if ($r.Content) { return $r.Content | ConvertFrom-Json }
    return $null
}

# 造数请求:忽略响应状态码(4xx/5xx 端点本就是被断言的对象)
function Send-Traffic($path, [int]$TimeoutSec = 30) {
    $null = Invoke-LocalHttp $path
}

# 就绪探测:优先 curl.exe(Windows 10 1803+ 自带)——不走 .NET/IE 代理、不受 Profile 对
# Invoke-WebRequest 的默认参数覆盖影响;回环地址用 --noproxy "*" 强制直连。
# 2s 超时对冷启动(agent 装配 + 首个被追踪请求)偏紧,放宽到 connect 3s / total 5s。
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

function Stop-App {
    if ($script:appProc -and -not $script:appProc.HasExited) {
        Stop-Process -Id $script:appProc.Id -Force -ErrorAction SilentlyContinue
        $script:appProc.WaitForExit(10000) | Out-Null
    }
}

Write-Host "==============================================================="
Write-Host " demo-app 验证回路 (ticket 05)"
Write-Host "   port=$Port  skipPluginBuild=$SkipPluginBuild  skipAppBuild=$SkipAppBuild  skipPluginInstall=$SkipPluginInstall"
Write-Host "==============================================================="

try {
    # ---- 1. 前置检查:agent / JDK / 端口 ----
    $agentDir = Resolve-AgentDir
    $agentJar = Join-Path $agentDir "skywalking-agent.jar"
    if (-not (Test-Path $agentJar)) {
        Write-Host "[FAIL] agent 目录无效(缺 skywalking-agent.jar): $agentDir`n       可用 -AgentDir 或环境变量 SKYWALKING_AGENT_DIR 指定。"
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

    # ---- 2. 构建:应用 jar(缺失时)+ 插件 jar + 安装进 agent plugins ----
    if (-not (Test-Path $appJar) -and -not $SkipAppBuild) {
        Write-Host "[..] demo-app jar 缺失,构建: mvn -f $demoAppDir\pom.xml clean package -DskipTests"
        Push-Location $demoAppDir
        mvn -f "$demoAppDir\pom.xml" clean package -DskipTests -q
        $code = $LASTEXITCODE
        Pop-Location
        if ($code -ne 0) { Write-Host "[FAIL] demo-app 构建失败 (exit=$code)"; exit 1 }
    }
    if (-not (Test-Path $appJar)) { Write-Host "[FAIL] demo-app jar 不存在: $appJar"; exit 1 }
    Write-Host "[OK] demo-app jar: $appJar"

    $agentPluginJar = Join-Path $agentDir "plugins\$pluginJarName"
    if ($SkipPluginInstall) {
        # 负向模式:故意移除插件 jar,预期在插件加载检查处大声失败(exit 3)
        Write-Host "[!] 负向模式(-SkipPluginInstall):移除 $agentPluginJar"
        Remove-Item $agentPluginJar -Force -ErrorAction SilentlyContinue
    } else {
        if (-not $SkipPluginBuild) {
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
            Write-Host "[FAIL] 插件 jar 不存在: $pluginJar`n       请先构建(去掉 -SkipPluginBuild)。"
            exit 1
        }
        Copy-Item $pluginJar $agentPluginJar -Force
        if (-not (Test-Path $agentPluginJar)) { Write-Host "[FAIL] 插件 jar 拷贝失败"; exit 1 }
        Write-Host "[OK] 插件已装入: $agentPluginJar"
    }

    # ---- 3. 清空 agent 运行时日志,启动应用 ----
    Remove-Item (Join-Path $agentDir "logs\skywalking-api.log") -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $agentDir "logs\skywalking-agent.log") -Force -ErrorAction SilentlyContinue

    $swArgs = @(
        "-javaagent:$agentJar",
        "-Dskywalking.agent.keep_tracing=true",
        "-Dskywalking.plugin.logfilereporter.alert.enabled=true",
        "-Dskywalking.plugin.logfilereporter.alert.slow_rules=operation:GET:/status/*=8000;operation:GET:/api/order/*=8000;operation:GET:/api/export/**=60000",
        "-Dskywalking.plugin.logfilereporter.alert.error_ignore_rules=operation:GET:/.well-known/**=404;operation:GET:/status/*=503,500,400;operation:GET:/api/exists/*=404;operation:GET:/inner/business-test/**=404,410"
    )
    $env:WebPort = "$Port"
    Write-Host "[..] 启动 demo-app (javaagent, WebPort=$Port): $base/"
    $script:appProc = Start-Process -FilePath $javaExe -ArgumentList ($swArgs + @("-jar", $appJar)) `
        -WorkingDirectory $demoAppDir -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru

    if (-not (Test-Ready "$base/" 90)) {
        Write-Host "[FAIL] 应用 90 秒内未就绪。"
        # 诊断:区分"应用根本没起/崩了"与"应用在听但探测不通"
        $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        if ($listener) {
            Write-Host "       [诊断] TCP $Port 正在监听(应用进程存活),但 curl 探测未拿到 200——"
            Write-Host "               请检查是否浏览器/IDE 也能正常访问 $base/(若可,则问题出在本脚本的探测路径,非应用本身)。"
        } else {
            Write-Host "       [诊断] TCP $Port 无监听,应用未成功启动。stderr 尾部:"
            Get-Content $errLog -ErrorAction SilentlyContinue | Select-Object -Last 15
            Write-Host "       stdout 尾部(应用日志):"
            Get-Content $outLog -ErrorAction SilentlyContinue | Select-Object -Last 15
        }
        exit 2
    }
    Write-Host "[OK] 应用就绪"

    $apiLog = Join-Path $agentDir "logs\skywalking-api.log"
    Start-Sleep -Seconds 2
    $loaded = Select-String -Path $apiLog -Pattern "logfile-reporter-plugin-[\d.]+\.jar loaded" -ErrorAction SilentlyContinue
    if (-not $loaded) {
        Write-Host "[FAIL] agent 日志未出现插件加载记录: $apiLog"
        if ($SkipPluginInstall) {
            Write-Host "[!] 负向模式验证达成:插件未安装时脚本大声失败(exit 3)。"
        } else {
            Write-Host "       请确认插件已构建并装入 plugins 目录,或检查 agent 日志:"
            Get-Content $apiLog -ErrorAction SilentlyContinue | Select-Object -Last 10
        }
        exit 3
    }
    Write-Host "[OK] 插件加载已验证: $($loaded[0].Line.Trim())"

    # ---- 4. 造数 + 断言 ----
    Write-Host ""
    Write-Host "---- 断言 A: trace 缓存合并(同一 traceId 多 segment 合并 + 字段齐全)----"

    $null = Invoke-LocalHttp "/api/trace-alert-demo/self-call"
    Start-Sleep -Seconds 3
    $stat = Get-Json "/statistic"
    $merged = $null
    foreach ($p in $stat.data.PSObject.Properties) {
        $logs = @($p.Value.logs)
        if ($logs.Count -ge 2) { $merged = $p; break }
    }
    Assert "存在 traceId 下合并了 >= 2 个 segment" ($null -ne $merged) "data 总 trace 数=$(@($stat.data.PSObject.Properties).Count)"
    if ($merged) {
        $logs = @($merged.Value.logs)
        $segIds = @($logs | ForEach-Object { $_.traceSegmentId })
        $sameTraceId = (@($logs | Where-Object { $_.traceId -ne $merged.Name }).Count -eq 0)
        $distinctSegs = (@($segIds | Sort-Object -Unique).Count -eq $segIds.Count)
        Assert "合并条目内各 segment traceId 一致且等于缓存键" $sameTraceId "traceId=$($merged.Name)"
        Assert "合并条目内 traceSegmentId 互不相同" $distinctSegs "segments=$($segIds -join ',')"
        $spans = @()
        foreach ($l in $logs) { $spans += @($l.spans) }
        $types = @($spans | ForEach-Object { $_.spanType }) | Sort-Object -Unique
        $ops = @($spans | ForEach-Object { $_.operationName })
        $fieldsOk = $true
        foreach ($s in $spans) {
            foreach ($f in @("spanId", "parentSpanId", "operationName", "startTime", "endTime", "spanType", "spanLayer", "componentId", "isError")) {
                if ($null -eq $s.$f) { $fieldsOk = $false }
            }
        }
        Assert "span 字段齐全(spanId/parentSpanId/operationName/时间/类型/componentId/isError)" $fieldsOk ""
        Assert "存在入口+出口两类 span(Entry/Exit)" (@($types | Where-Object { $_ -in @("Entry", "Exit") }).Count -eq 2) "types=$($types -join ',')"
        Assert "入口为 self-call,出口指向应用自身 ok 端点" ($ops -contains "GET:/api/trace-alert-demo/self-call" -and (@($ops | Where-Object { $_ -match "/api/trace-alert-demo/ok" }).Count -gt 0)) "ops=$($ops -join ' | ')"
    }

    Write-Host ""
    Write-Host "---- 断言 B: 告警链端到端(slow / error / ignore-rule / webhook 收讫)----"

    $null = Invoke-LocalHttp "/inner/sw/trace-alert/clear" "POST"
    Send-Traffic "/api/trace-alert-demo/slow?ms=4000"
    Send-Traffic "/api/order/1"
    Send-Traffic "/api/trace-alert-demo/error"
    Send-Traffic "/api/trace-alert-demo/http500"
    Send-Traffic "/status/500"
    Send-Traffic "/api/trace-alert-demo/ok"

    $events = $null
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Seconds 2
        $events = @((Get-Json "/inner/sw/trace-alert/recent").events)
        if ($events.Count -ge 4) { break }
    }
    $eventDetail = @($events | ForEach-Object { "$($_.alertTypes -join '+')<$($_.url)>" }) -join "`n       "
    Assert "webhook 收讫 >= 4 条告警事件(2 SLOW + 2 ERROR)" ($events.Count -ge 4) "count=$($events.Count)`n       $eventDetail"
    Assert "慢请求(默认阈值)触发 SLOW" (@($events | Where-Object { $_.alertTypes -contains "SLOW" -and $_.url -like "*api/trace-alert-demo/slow*" }).Count -ge 1) "见事件明细"
    Assert "慢请求(Ant 规则 /api/order/*=8000)触发 SLOW" (@($events | Where-Object { $_.alertTypes -contains "SLOW" -and $_.url -like "*api/order/1*" }).Count -ge 1) "见事件明细"
    Assert "未捕获异常(isError)触发 ERROR" (@($events | Where-Object { $_.alertTypes -contains "ERROR" -and $_.url -like "*api/trace-alert-demo/error*" }).Count -ge 1) "见事件明细"
    Assert "HTTP 500 触发 ERROR" (@($events | Where-Object { $_.alertTypes -contains "ERROR" -and $_.url -like "*api/trace-alert-demo/http500*" }).Count -ge 1) "见事件明细"
    Assert "豁免规则 /status/500=503,500,400 命中 -> 无事件" (@($events | Where-Object { $_.url -like "*status/500*" }).Count -eq 0) "见事件明细"
    Assert "对照请求 /ok -> 无事件" (@($events | Where-Object { $_.url -like "*api/trace-alert-demo/ok*" }).Count -eq 0) "见事件明细"

    $statAlert = Get-Json "/statisticTraceAlert"
    Assert "端到端闭环:webhook 尝试次数 >= 4" ($statAlert.httpWebhook.totalAttempts -ge 4) "totalAttempts=$($statAlert.httpWebhook.totalAttempts)"
    Assert "端到端闭环:webhook 全部成功(successCount >= 4)" ($statAlert.httpWebhook.successCount -ge 4) "successCount=$($statAlert.httpWebhook.successCount)"
    Assert "告警分发计数 >= 4(dispatcher.dispatchSubmitted)" ($statAlert.dispatcher.dispatchSubmitted -ge 4) "submitted=$($statAlert.dispatcher.dispatchSubmitted)"

    Write-Host ""
    Write-Host "---- 断言 C: 运行时开关(disable 停止增长 / enable 恢复)----"

    function DataCount {
        $s = Get-Json "/statistic"
        if (@($s.PSObject.Properties.Name) -contains "plugin") { return -1 }
        return @($s.data.PSObject.Properties).Count
    }
    $c1 = DataCount
    $null = Invoke-LocalHttp "/toggle?enable=false" "POST"
    Start-Sleep -Seconds 4
    1..3 | ForEach-Object { $null = Invoke-LocalHttp "/" }
    Start-Sleep -Seconds 4
    $c2 = DataCount
    $null = Invoke-LocalHttp "/toggle?enable=true" "POST"
    Start-Sleep -Seconds 4
    1..3 | ForEach-Object { $null = Invoke-LocalHttp "/" }
    Start-Sleep -Seconds 4
    $c3 = DataCount
    Assert "关闭后统计快照停止增长(容忍自读噪声 +2)" (($c2 - $c1) -le 2) "before=$c1 disabled=$c2 grow=$($c2 - $c1)"
    Assert "开启后统计快照恢复增长(>= 3)" (($c3 - $c2) -ge 3) "disabled=$c2 enabled=$c3 grow=$($c3 - $c2)"

    Write-Host ""
    Write-Host "---- 断言 D: 数据流读口冒烟(五类 + 告警运行态)----"

    $jvm = Get-Json "/statisticJVM"
    Assert "JVM 指标有数据" (@($jvm).Count -gt 0) "points=$(@($jvm).Count)"
    $meter = Get-Json "/statisticMeter"
    Assert "meter 指标有数据" (@($meter.PSObject.Properties).Count -gt 0) "keys=$(@($meter.PSObject.Properties).Count)"
    $logs = Get-Json "/statisticLogs"
    Assert "应用日志有数据" (@($logs).Count -gt 0) "entries=$(@($logs).Count)"
    $props = Get-Json "/statisticInstanceProperties"
    Assert "实例属性有数据" (@($props.PSObject.Properties).Count -gt 0) "keys=$(@($props.PSObject.Properties).Count)"
    Assert "告警运行态 enabled" ($statAlert.config.enabled -eq $true) "enabled=$($statAlert.config.enabled)"

    # ---- 5. 报告 ----
    Write-Host ""
    Write-Host "==============================================================="
    if ($script:failures -eq 0) {
        Write-Host " 验证回路全绿 (exit 0)"
    } else {
        Write-Host " 验证失败: $($script:failures) 项断言未通过 (exit 5)"
    }
    Write-Host "==============================================================="
} finally {
    Stop-App
}

if ($script:failures -ne 0) { exit 5 }
exit 0
