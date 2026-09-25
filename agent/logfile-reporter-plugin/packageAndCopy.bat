@echo off
rem ============================================================
rem  packageAndCopy.bat - build logfile-reporter-plugin and copy
rem  the jar into the local SkyWalking agent.
rem
rem  HOW TO RUN (from cmd.exe, inside logfile-reporter-plugin):
rem      cd agent\logfile-reporter-plugin
rem      packageAndCopy.bat
rem  (it also works when double-clicked or run from any folder)
rem
rem  WHAT IT DOES:
rem     [1/3] build the plugin, reactor root = agent dir:
rem             mvn clean package -Dmaven.test.skip=true -T 2C
rem                -pl logfile-reporter-plugin -am
rem     [2/3] copy the built jar to
rem             D:\apps\apache-skywalking-java-agent-9.4.0\plugins\
rem     [3/3] list the installed logfile-reporter-plugin jars.
rem
rem  Copy target is fixed to the D: drive.
rem  NOTE: keep this file pure ASCII. cmd.exe parses batch files
rem  with the local codepage, so UTF-8 multibyte text (Chinese)
rem  gets misread as GBK and corrupts the commands. Do NOT add
rem  any non-ASCII character to this file.
rem ============================================================

setlocal

set ROOT=D:
set AGENT_DIR=%ROOT%\apps\apache-skywalking-java-agent-9.4.0
rem set JDK17=%ROOT%\apps\java\jdk-17.0.8
rem if defined JDK17 set "PATH=%JDK17%\bin;%PATH%"

rem repo paths are derived from this script's own location,
rem so it works no matter which folder it is run from.
set BATCH_DIR=%~dp0
set REPO_AGENT=%BATCH_DIR%..
set MODULE_DIR=%REPO_AGENT%\logfile-reporter-plugin
set JAR_FILE=%MODULE_DIR%\target\logfile-reporter-plugin-1.0.0.jar

echo [1/3] build plugin, reactor root = %REPO_AGENT%
cd /d "%REPO_AGENT%"
rem use "call": mvn is a batch file (mvn.cmd) and without "call"
rem the control never returns to this script - the copy steps below
rem would be skipped silently.
call mvn clean package "-Dmaven.test.skip=true" -T 2C -pl logfile-reporter-plugin -am
if errorlevel 1 goto :fail

echo [2/3] copy jar to agent plugins dir
if not exist "%AGENT_DIR%\plugins\" (
    echo ERROR: agent plugins dir not found: %AGENT_DIR%\plugins\
    goto :fail
)
copy /Y "%JAR_FILE%" "%AGENT_DIR%\plugins\logfile-reporter-plugin-1.0.0.jar" >nul
if errorlevel 1 goto :fail

echo [3/3] installed logfile-reporter-plugin jars:
dir /b "%AGENT_DIR%\plugins\logfile-reporter-plugin-*.jar"
goto :eof

:fail
echo Build or copy failed, check the error output above.
exit /b 1