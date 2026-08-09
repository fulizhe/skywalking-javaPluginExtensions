# 演示应用启动脚本(应用层面,不含 agent 装配)
# agent 装配与验证回路见后续工单(02/05/08)
param(
    [int]$Port = 9600
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root "target\demo-app-1.0.0.jar"
if (-not (Test-Path -LiteralPath $jar)) {
    throw "jar 不存在: $jar,请先执行: mvn -f $root\pom.xml clean package"
}
$env:WebPort = "$Port"
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
& $java -jar $jar
