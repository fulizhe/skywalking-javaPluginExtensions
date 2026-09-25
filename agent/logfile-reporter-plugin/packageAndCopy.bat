@echo off
rem ============================================================
rem  编译并拷贝 logfile-reporter-plugin 到 SkyWalking agent
rem  拷贝目标固定为 D 盘
rem  仓库路径从脚本自身所在目录推导，无需硬编码
rem ============================================================

rem 固定使用 D 盘
set ROOT=D:

set AGENT_DIR=%ROOT%\apps\apache-skywalking-java-agent-9.4.0
rem set JDK17=%ROOT%\apps\java\jdk-17.0.8
rem if defined JDK17 set "PATH=%JDK17%\bin;%PATH%"

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
