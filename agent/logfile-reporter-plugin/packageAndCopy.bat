@echo off
rem ============================================================
rem  Build and copy logfile-reporter-plugin into the SkyWalking agent.
rem  Copy target is fixed to D: drive.
rem  Repo location is derived from this script's own path.
rem  NOTE: keep this file pure ASCII - cmd parses batches per codepage,
rem  any UTF-8 multibyte comment gets misread as GBK and breaks lines.
rem  Do NOT add non-ASCII characters to this file.
rem ============================================================

set ROOT=D:

set AGENT_DIR=%ROOT%\apps\apache-skywalking-java-agent-9.4.0
rem set JDK17=%ROOT%\apps\java\jdk-17.0.8
rem if defined JDK17 set "PATH=%JDK17%\bin;%PATH%"

rem repo agent dir = parent of this script
set REPO_AGENT=%~dp0..

cd /d "%REPO_AGENT%"

mvn clean package "-Dmaven.test.skip=true" -T 2C -pl logfile-reporter-plugin -am
if errorlevel 1 goto :fail

cp .\logfile-reporter-plugin\target\logfile-reporter-plugin-1.0.0.jar "%AGENT_DIR%\plugins\logfile-reporter-plugin-1.0.0.jar"
if errorlevel 1 goto :fail

ls "%AGENT_DIR%\plugins\" | findstr logfile-reporter-plugin-
goto :eof

:fail
echo Build or copy failed, check error output above.
exit /b 1