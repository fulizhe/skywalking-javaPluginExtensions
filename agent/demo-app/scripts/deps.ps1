# 依赖拓扑演示的本地手工台:起 deps profile 的三层中间件 + 打造数端点
#
# 面向"在 Windows 上手工验证依赖拓扑"的场景。compose 的 deps profile 是主路径,
# 本脚本是**同一条路径的 Windows 包装**(不另立中间件实现),补三件 compose 之外的事:
#   1) 一条命令起/停/看状态,不用记 compose 参数
#   2) -Smoke 直接打四组造数端点并打表(ok / 耗时 / 失败原因),人工看图前先跑一次
#   3) Docker 不可用时**不失败**:直接提示"仍可打 -Smoke 看红边" —— 中间件不在场
#      照样出依赖边(红边),这是该页的既定口径,不是故障
#
# 用法(pwsh / PowerShell 7;Windows PowerShell 5.1 控制台可能把中文输出按 GBK 解码):
#   pwsh ./scripts/deps.ps1 -Up                 # 起 redis / mysql / kafka(docker)
#   pwsh ./scripts/deps.ps1 -Status             # 看三件套状态
#   pwsh ./scripts/deps.ps1 -Smoke              # 打四组造数端点(应用需已在跑)
#   pwsh ./scripts/deps.ps1 -All                # -Up 后自动 -Smoke
#   pwsh ./scripts/deps.ps1 -Down               # 停并删中间件容器(volume 保留)
#   pwsh ./scripts/deps.ps1 -Smoke -BaseUrl http://127.0.0.1:9601
#
# 中间件端口(容器内 → 宿主机):redis 6379 / mysql 3306 / kafka 9092。
# 本机跑应用时用 application.yml 的 localhost 默认值即可,**不需要**设 DEPS_* 环境变量;
# 只有从 compose 里的 demo-app 容器访问才用服务名(见 docker-compose.yaml)。
#
# 退出码:0 成功(或 -Smoke 全部端点都返回);1 Docker 不可用/操作失败;2 应用未就绪。
param(
    # 起中间件(docker compose --profile deps up -d)
    [switch]$Up,
    # 停并删中间件容器
    [switch]$Down,
    # 打印中间件状态
    [switch]$Status,
    # 打四组造数端点并打表
    [switch]$Smoke,
    # -Up + -Smoke
    [switch]$All,
    # 演示应用地址(Smoke 用)
    [string]$BaseUrl = "http://127.0.0.1:9600",
    # 外呼站点(白名单:httpbin / baidu / google);外网不通属预期,会显示为红边
    [string]$Site = "httpbin",
    # 造慢边的 sleepMs(MySQL 专用,上限 3000)
    [int]$SleepMs = 300
)

$ErrorActionPreference = "Stop"

$demoAppDir = Split-Path -Parent $PSScriptRoot          # agent/demo-app
$composeFile = Join-Path $demoAppDir "docker-compose.yaml"
$base = $BaseUrl.TrimEnd('/')

# deps profile 的三件套:服务名 → 宿主端口(仅用于状态提示)
$DEPS_SERVICES = @(
    @{ Name = "redis"; Port = 6379; Layer = "Cache";   Component = "Redis" },
    @{ Name = "mysql"; Port = 3306; Layer = "Database"; Component = "MySQL" },
    @{ Name = "kafka"; Port = 9092; Layer = "MQ";      Component = "Kafka" }
)

function Get-ComposeCommand {
    # 优先 docker compose v2;退回 docker-compose v1(compose 文件本身是 v2 语法,老版本可能不支持 profiles)
    if (Get-Command docker -ErrorAction SilentlyContinue) { return @("docker", "compose") }
    if (Get-Command docker-compose -ErrorAction SilentlyContinue) { return @("docker-compose") }
    return $null
}

function Test-DockerAvailable {
    $compose = Get-ComposeCommand
    if (-not $compose) {
        Write-Host "[FAIL] 未找到 docker / docker-compose。"
        return $false
    }
    & $compose[0] $compose[1..($compose.Count - 1)] version 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[FAIL] Docker 不可用(Docker Desktop 未运行?)。"
        Write-Host "       仍然可以继续:`pwsh ./scripts/deps.ps1 -Smoke` —— 中间件不在场时"
        Write-Host "       Kafka 外呼与 Hutool 外呼**照样出边**(调用被观测到);而 Redis / MySQL"
        Write-Host "       因为**连接都没建起来**不产生依赖边,图上该组件缺席(不是'没有调用')。"
        return $false
    }
    return $true
}

function Invoke-Compose {
    param([string[]]$ComposeArgs)
    $compose = Get-ComposeCommand
    Push-Location $demoAppDir
    try {
        & $compose[0] @($compose[1..($compose.Count - 1)]) @ComposeArgs
        return $LASTEXITCODE
    } finally {
        Pop-Location
    }
}

function Show-DepsStatus {
    Write-Host ""
    Write-Host "== deps profile 状态 =="
    $running = @()
    try {
        $out = & docker ps --filter "label=com.docker.compose.project=demo-app-stability" --format "{{.Names}}`t{{.Status}}" 2>&1
        if ($LASTEXITCODE -eq 0) { $running = @($out) }
    } catch {
        # docker 不可用时下面按"未运行"处理
    }
    foreach ($svc in $DEPS_SERVICES) {
        $hit = $running | Where-Object { $_ -match $svc.Name }
        $state = if ($hit) { ($hit -join "; ") } else { "未运行(该层将表现为红边)" }
        Write-Host ("  {0,-6} {1,-9} 宿主端口 {2,-5} 组件 {3,-6} {4}" -f `
            $svc.Name, $svc.Layer, $svc.Port, $svc.Component, $state)
    }
}

function Get-SmokePaths {
    # 与 README 的造数端点表一一对应;mysql 走 sleepMs 造一条慢边
    return @(
        @{ Label = "Cache   Redis  set";  Path = "/api/deps-demo/redis?op=set" },
        @{ Label = "Cache   Redis  get";  Path = "/api/deps-demo/redis?op=get" },
        @{ Label = "Cache   Redis  del";  Path = "/api/deps-demo/redis?op=del" },
        @{ Label = "Database MySQL select"; Path = "/api/deps-demo/mysql" },
        @{ Label = "Database MySQL sleep"; Path = "/api/deps-demo/mysql?sleepMs=$SleepMs" },
        @{ Label = "MQ      Kafka  produce"; Path = "/api/deps-demo/kafka?op=produce" },
        @{ Label = "MQ      Kafka  consume"; Path = "/api/deps-demo/kafka?op=consume" },
        @{ Label = "Http    外呼    $Site"; Path = "/api/deps-demo/http?site=$Site" }
    )
}

# 应用就绪探测:与 stress.ps1 同款(取 HTTP 状态码比对,不依赖 $LASTEXITCODE)
function Test-AppReady {
    param([string]$Url)
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
        $code = & curl.exe -s -o NUL -w "%{http_code}" --noproxy "*" --connect-timeout 3 --max-time 5 $Url 2>$null
        return "$code" -eq "200"
    }
    try { return (Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200 } catch { return $false }
}

function Invoke-Smoke {
    Write-Host ""
    Write-Host "== 造数端点冒烟(BaseUrl=$base) =="
    if (-not (Test-AppReady "$base/")) {
        Write-Host "[FAIL] 应用未就绪:$base。请先 pwsh ./scripts/run-with-agent.ps1"
        return 2
    }

    $failed = 0
    foreach ($item in (Get-SmokePaths)) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $raw = & curl.exe -s --noproxy "*" --max-time 15 "$base$($item.Path)" 2>$null
        $sw.Stop()
        $ok = $false
        $detail = ""
        # 刻意**不用 ConvertFrom-Json 判定**:外呼端点的 detail 是一段完整 JSON 文本
        # (httpbin 回显 headers,含同名 header 时 PS 的 ConvertFrom-Json 会因重复键抛错),
        # 结果是明明 ok:true 被打成失败。正则只认外层的 "ok":true,稳。
        $body = ($raw -join "`n")
        $ok = ($body -match '"ok"\s*:\s*true')
        if ($ok) {
            if ($body -match '"detail"\s*:\s*("(?:[^"\\]|\\.)*")') { $detail = $Matches[1] }
        } elseif ($body -match '"error"\s*:\s*("(?:[^"\\]|\\.)*")') {
            $detail = $Matches[1]
        } else {
            $detail = $body
        }
        if (-not $ok) { $failed++ }
        $flag = if ($ok) { " " } else { "!" }
        $detail = (($detail -replace '\s+', ' ').Trim())
        if ($detail.Length -gt 110) { $detail = $detail.Substring(0, 109) + "…" }
        Write-Host ("{0} {1,-24} {2,6} ms  {3}" -f $flag, $item.Label, $sw.ElapsedMilliseconds, $detail)
    }
    Write-Host ""
    if ($failed -eq 0) {
        Write-Host "[PASS] 全部端点连通。现在打开 $base/dashboards/topology.html 看依赖图。"
    } else {
        Write-Host "[WARN] $failed 个端点失败 —— 中间件未起或外网不通时这是预期的,但要分清两种结果:"
        Write-Host "       [有边]  Kafka produce / Hutool 外呼:调用本身被观测到 -> 图上有边(错误率可能仍显示 0)"
        Write-Host "       [无边]  Redis / MySQL:连接都没建起来 -> **不产生依赖边**,图上该组件缺席。"
        Write-Host "               缺席**不能**读成'没有这个调用'(出口 span 在连接成功之后才建立)。"
        Write-Host "       起中间件: pwsh ./scripts/deps.ps1 -Up"
    }
    return 0
}

# ------------------------------------------------------------------ 主流程

$doStatus = $Status -or (-not ($Up -or $Down -or $Smoke -or $All))

if ($Up -or $All -or $Down) {
    if (-not (Test-DockerAvailable)) {
        if ($All -or $Smoke) {
            # 不因为 Docker 缺席而挡住冒烟:红边本身就是可验的产物
            $null = Invoke-Smoke
            exit 1
        }
        exit 1
    }
    if ($Down) {
        Write-Host "[..] 停止 deps profile 中间件"
        $rc = Invoke-Compose @("--profile", "deps", "down")
        exit $rc
    }
    Write-Host "[..] 起 deps profile 中间件(redis / mysql / kafka)"
    $rc = Invoke-Compose @("--profile", "deps", "up", "-d")
    if ($rc -ne 0) { exit $rc }
    Write-Host "[..] 等待中间件就绪(最多 90s)"
    for ($i = 0; $i -lt 90; $i++) {
        $ps = & docker ps --filter "label=com.docker.compose.project=demo-app-stability" --format "{{.Names}}" 2>&1
        $names = @($ps)
        $ready = 0
        foreach ($svc in $DEPS_SERVICES) {
            if ($names | Where-Object { $_ -match $svc.Name }) { $ready++ }
        }
        if ($ready -eq $DEPS_SERVICES.Count) { break }
        Start-Sleep -Seconds 1
    }
}

if ($doStatus) { Show-DepsStatus }
if ($All) { Show-DepsStatus }
if ($Smoke -or $All) { exit (Invoke-Smoke) }
if ($Up) { Show-DepsStatus }
exit 0