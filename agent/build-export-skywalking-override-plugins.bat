@echo off
setlocal enabledelayedexpansion

rem ---------------------------------------------------------------------------
rem Build and export the custom SkyWalking override plugins used in this repo.
rem
rem Usage:
rem   build-export-skywalking-override-plugins.bat
rem   build-export-skywalking-override-plugins.bat "D:\apps\apache-skywalking-java-agent-9.4.0" "D:\apps\java\jdk1.8.0_172"
rem
rem Arg1: SkyWalking agent home. Default:
rem       D:\apps\apache-skywalking-java-agent-9.4.0
rem Arg2: JDK home. Default:
rem       D:\apps\java\jdk1.8.0_172
rem ---------------------------------------------------------------------------

set "SCRIPT_DIR=%~dp0"
set "REPO_ROOT=%SCRIPT_DIR%"

if "%~1"=="" (
    set "SKYWALKING_AGENT_HOME=D:\apps\apache-skywalking-java-agent-9.4.0"
) else (
    set "SKYWALKING_AGENT_HOME=%~1"
)

if "%~2"=="" (
    set "JDK_HOME=D:\apps\java\jdk1.8.0_172"
) else (
    set "JDK_HOME=%~2"
)

set "PLUGINS_DIR=%SKYWALKING_AGENT_HOME%\plugins"
set "OPTIONAL_PLUGINS_DIR=%SKYWALKING_AGENT_HOME%\optional-plugins"
set "AGENT_LOG_FILE=%SKYWALKING_AGENT_HOME%\logs\skywalking-api.log"

set "HTTPCLIENT_MODULE=override-httpclient-4.x-plugin"
set "HTTPCLIENT_JAR=override-apm-httpclient-4.x-plugin-9.4.0.jar"
set "HTTPCLIENT_OFFICIAL_JAR=apm-httpClient-4.x-plugin-9.4.0.jar"

set "HUTOOL_MODULE=override-hutool-http-5.x-plugin"
set "HUTOOL_JAR=override-apm-hutool-http-5.x-plugin-9.4.0.jar"
set "HUTOOL_OFFICIAL_JAR=apm-hutool-http-5.x-plugin-9.4.0.jar"

if not exist "%REPO_ROOT%pom.xml" (
    echo [ERROR] Cannot find pom.xml in repo root: "%REPO_ROOT%"
    exit /b 1
)

if not exist "%JDK_HOME%\bin\java.exe" (
    echo [ERROR] JDK not found: "%JDK_HOME%"
    exit /b 1
)

where mvn >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Maven not found in PATH.
    exit /b 1
)

if not exist "%PLUGINS_DIR%" (
    echo [ERROR] SkyWalking plugins directory not found: "%PLUGINS_DIR%"
    exit /b 1
)

if not exist "%OPTIONAL_PLUGINS_DIR%" (
    mkdir "%OPTIONAL_PLUGINS_DIR%"
    if errorlevel 1 (
        echo [ERROR] Failed to create optional-plugins directory: "%OPTIONAL_PLUGINS_DIR%"
        exit /b 1
    )
)

set "PATH=%JDK_HOME%\bin;%PATH%"

echo.
echo [INFO] Repo root: "%REPO_ROOT%"
echo [INFO] SkyWalking agent home: "%SKYWALKING_AGENT_HOME%"
echo [INFO] JDK home: "%JDK_HOME%"
echo.

pushd "%REPO_ROOT%"
if errorlevel 1 (
    echo [ERROR] Failed to enter repo root.
    exit /b 1
)

call :build_module "%HTTPCLIENT_MODULE%"
if errorlevel 1 goto :fail

call :build_module "%HUTOOL_MODULE%"
if errorlevel 1 goto :fail

call :export_plugin "%HTTPCLIENT_MODULE%\target\%HTTPCLIENT_JAR%" "%HTTPCLIENT_JAR%" "%HTTPCLIENT_OFFICIAL_JAR%"
if errorlevel 1 goto :fail

call :export_plugin "%HUTOOL_MODULE%\target\%HUTOOL_JAR%" "%HUTOOL_JAR%" "%HUTOOL_OFFICIAL_JAR%"
if errorlevel 1 goto :fail

echo.
echo [INFO] Export completed successfully.
echo [INFO] Current custom plugin jars:
dir /b "%PLUGINS_DIR%\override-apm-*.jar"
echo.
if exist "%AGENT_LOG_FILE%" (
    echo [INFO] Recent related log lines:
    powershell -NoProfile -Command ^
        "Get-Content -Path '%AGENT_LOG_FILE%' -Tail 200 | Select-String -Pattern 'override-apm-httpclient-4.x-plugin|override-apm-hutool-http-5.x-plugin'"
) else (
    echo [WARN] SkyWalking log file not found yet: "%AGENT_LOG_FILE%"
)

popd
exit /b 0

:build_module
set "MODULE_NAME=%~1"
echo [INFO] Building module: %MODULE_NAME%
call mvn clean package "-Dmaven.test.skip=true" -T 2C -pl "%MODULE_NAME%" -am
if errorlevel 1 (
    echo [ERROR] Build failed: %MODULE_NAME%
    exit /b 1
)
exit /b 0

:export_plugin
set "SOURCE_JAR=%~1"
set "TARGET_JAR_NAME=%~2"
set "OFFICIAL_JAR_NAME=%~3"

if not exist "%SOURCE_JAR%" (
    echo [ERROR] Built jar not found: "%SOURCE_JAR%"
    exit /b 1
)

echo [INFO] Copying "%TARGET_JAR_NAME%" to SkyWalking plugins directory
copy /Y "%SOURCE_JAR%" "%PLUGINS_DIR%\%TARGET_JAR_NAME%" >nul
if errorlevel 1 (
    echo [ERROR] Copy failed: "%SOURCE_JAR%"
    exit /b 1
)

if exist "%PLUGINS_DIR%\%OFFICIAL_JAR_NAME%" (
    echo [INFO] Moving official plugin to optional-plugins: "%OFFICIAL_JAR_NAME%"
    move /Y "%PLUGINS_DIR%\%OFFICIAL_JAR_NAME%" "%OPTIONAL_PLUGINS_DIR%\%OFFICIAL_JAR_NAME%" >nul
    if errorlevel 1 (
        echo [ERROR] Failed to move official plugin: "%OFFICIAL_JAR_NAME%"
        exit /b 1
    )
)

exit /b 0

:fail
popd
exit /b 1
