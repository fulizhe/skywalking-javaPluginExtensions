@echo off
rem ============================================================
rem  编译并拷贝 logfile-reporter-plugin 到 SkyWalking agent
rem  盘符自动探测：存在 E 盘则用 E，否则用 D（本机无 E 盘，故落 D）
rem  仓库路径从脚本自身所在目录推导，无需硬编码
rem ============================================================

rem 自动选盘（存在 E 盘用 E，否则用 D）
if exist E:\ (
    set ROOT=E:
) else (
    set ROOT=D:
)

set AGENT_DIR=%ROOT%\apps\apache-skywalking-java-agent-9.4.0
rem set JDK8=%ROOT%\apps\java\jdk1.8.0_172
rem if defined JDK8 set "PATH=%JDK8%\bin;%PATH%"

rem 仓库 agent 目录 = 脚本目录的上一级
set REPO_AGENT=%~dp0..

cd /d "%REPO_AGENT%"

mvn clean package "-Dmaven.test.skip=true" -T 2C -pl logfile-reporter-plugin -am
if errorlevel 1 goto :fail

cp .\logfile-reporter-plugin\target\logfile-reporter-plugin-1.0.0.jar "%AGENT_DIR%\plugins\logfile-reporter-plugin-1.0.0.jar"
if errorlevel 1 goto :fail

ls "%AGENT_DIR%\plugins\" | findstr logfile-reporter-plugin-
goto :eof

:fail
echo 编译或拷贝失败，请检查错误信息。
exit /b 1
