@echo off
setlocal enabledelayedexpansion

rem ---------------------------------------------------------------------------
rem Build and export the custom SkyWalking override plugins used in this repo.
rem
rem Usage:
rem   build-export-skywalking-override-plugins.bat
rem   build-export-skywalking-override-plugins.bat "D:\apps\apache-skywalking-java-agent-9.4.0" "D:\apps\java\jdk-17.0.8"
rem
rem Arg1: SkyWalking agent home. Default:
rem       D:\apps\apache-skywalking-java-agent-9.4.0
rem Arg2: JDK home (build toolchain, release 8 requires JDK 9+). Default:
rem       D:\apps\java\jdk-17.0.8
rem ---------------------------------------------------------------------------

set "SCRIPT_DIR=%~dp0"
set "REPO_ROOT=%SCRIPT_DIR%"

if "%~1"=="" (
    set "SKYWALKING_AGENT_HOME=D:\apps\apache-skywalking-java-agent-9.4.0"
) else (
    set "SKYWALKING_AGENT_HOME=%~1"
)

if "%~2"=="" (
    set "JDK_HOME=D:\apps\java\jdk-17.0.8"
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

rem --- step status (OK / FAILED / SKIPPED) ---
set "STATUS_BUILD_HTTPCLIENT=SKIPPED"
set "STATUS_BUILD_HUTOOL=SKIPPED"
set "STATUS_EXPORT_HTTPCLIENT=SKIPPED"
set "STATUS_EXPORT_HUTOOL=SKIPPED"
set "FAIL_STEP="
set "DID_PUSHD=0"

rem --- export detail (filled after successful export) ---
set "EXPORT_HTTPCLIENT_FROM="
set "EXPORT_HTTPCLIENT_TO="
set "EXPORT_HTTPCLIENT_OFFICIAL="
set "EXPORT_HUTOOL_FROM="
set "EXPORT_HUTOOL_TO="
set "EXPORT_HUTOOL_OFFICIAL="

if not exist "%REPO_ROOT%pom.xml" (
    set "FAIL_STEP=pre-check - pom.xml not found"
    goto :summary
)

if not exist "%JDK_HOME%\bin\java.exe" (
    set "FAIL_STEP=pre-check - JDK not found: %JDK_HOME%"
    goto :summary
)

where mvn >nul 2>nul
if errorlevel 1 (
    set "FAIL_STEP=pre-check - Maven not found in PATH"
    goto :summary
)

if not exist "%PLUGINS_DIR%" (
    set "FAIL_STEP=pre-check - plugins directory not found: %PLUGINS_DIR%"
    goto :summary
)

if not exist "%OPTIONAL_PLUGINS_DIR%" (
    mkdir "%OPTIONAL_PLUGINS_DIR%"
    if errorlevel 1 (
        set "FAIL_STEP=pre-check - cannot create optional-plugins: %OPTIONAL_PLUGINS_DIR%"
        goto :summary
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
    set "FAIL_STEP=pre-check - cannot enter repo root"
    goto :summary
)
set "DID_PUSHD=1"

call :build_module "%HTTPCLIENT_MODULE%"
if errorlevel 1 (
    set "STATUS_BUILD_HTTPCLIENT=FAILED"
    set "FAIL_STEP=build %HTTPCLIENT_MODULE%"
    goto :summary
)
set "STATUS_BUILD_HTTPCLIENT=OK"

call :build_module "%HUTOOL_MODULE%"
if errorlevel 1 (
    set "STATUS_BUILD_HUTOOL=FAILED"
    set "FAIL_STEP=build %HUTOOL_MODULE%"
    goto :summary
)
set "STATUS_BUILD_HUTOOL=OK"

call :export_plugin "%HTTPCLIENT_MODULE%\target\%HTTPCLIENT_JAR%" "%HTTPCLIENT_JAR%" "%HTTPCLIENT_OFFICIAL_JAR%"
if errorlevel 1 (
    set "STATUS_EXPORT_HTTPCLIENT=FAILED"
    set "FAIL_STEP=export %HTTPCLIENT_JAR%"
    goto :summary
)
set "STATUS_EXPORT_HTTPCLIENT=OK"
set "EXPORT_HTTPCLIENT_FROM=!LAST_EXPORT_FROM!"
set "EXPORT_HTTPCLIENT_TO=!LAST_EXPORT_TO!"
set "EXPORT_HTTPCLIENT_OFFICIAL=!LAST_EXPORT_OFFICIAL!"

call :export_plugin "%HUTOOL_MODULE%\target\%HUTOOL_JAR%" "%HUTOOL_JAR%" "%HUTOOL_OFFICIAL_JAR%"
if errorlevel 1 (
    set "STATUS_EXPORT_HUTOOL=FAILED"
    set "FAIL_STEP=export %HUTOOL_JAR%"
    goto :summary
)
set "STATUS_EXPORT_HUTOOL=OK"
set "EXPORT_HUTOOL_FROM=!LAST_EXPORT_FROM!"
set "EXPORT_HUTOOL_TO=!LAST_EXPORT_TO!"
set "EXPORT_HUTOOL_OFFICIAL=!LAST_EXPORT_OFFICIAL!"

goto :summary

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
set "LAST_EXPORT_FROM=%SOURCE_JAR%"
set "LAST_EXPORT_TO=%PLUGINS_DIR%\%TARGET_JAR_NAME%"
set "LAST_EXPORT_OFFICIAL=skipped - official jar not in plugins: %OFFICIAL_JAR_NAME%"

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
    set "LAST_EXPORT_OFFICIAL=moved to %OPTIONAL_PLUGINS_DIR%\%OFFICIAL_JAR_NAME%"
)

exit /b 0

:summary
echo.
echo ============================================================================
echo  BUILD / EXPORT SUMMARY
echo ============================================================================
echo  SkyWalking agent : %SKYWALKING_AGENT_HOME%
echo  Plugins dir      : %PLUGINS_DIR%
echo  Optional-plugins : %OPTIONAL_PLUGINS_DIR%
echo ----------------------------------------------------------------------------

if defined FAIL_STEP (
    echo  Overall result   : FAILED
    echo  Failed step      : !FAIL_STEP!
) else (
    echo  Overall result   : SUCCESS
)

echo ----------------------------------------------------------------------------

echo  [Step-1] Build %HTTPCLIENT_MODULE%
echo       status         : %STATUS_BUILD_HTTPCLIENT%

echo  [Step-2] Build %HUTOOL_MODULE%
echo       status         : %STATUS_BUILD_HUTOOL%

echo  [Step-3] Export %HTTPCLIENT_JAR%
echo       status         : %STATUS_EXPORT_HTTPCLIENT%
if "%STATUS_EXPORT_HTTPCLIENT%"=="OK" (
    echo       built jar      : !EXPORT_HTTPCLIENT_FROM!
    echo       copied to      : !EXPORT_HTTPCLIENT_TO!
    echo       official jar   : !EXPORT_HTTPCLIENT_OFFICIAL!
)
if "%STATUS_EXPORT_HTTPCLIENT%"=="FAILED" (
    echo       intended target  : %PLUGINS_DIR%\%HTTPCLIENT_JAR%
)

echo  [Step-4] Export %HUTOOL_JAR%
echo       status         : %STATUS_EXPORT_HUTOOL%
if "%STATUS_EXPORT_HUTOOL%"=="OK" (
    echo       built jar      : !EXPORT_HUTOOL_FROM!
    echo       copied to      : !EXPORT_HUTOOL_TO!
    echo       official jar   : !EXPORT_HUTOOL_OFFICIAL!
)
if "%STATUS_EXPORT_HUTOOL%"=="FAILED" (
    echo       intended target  : %PLUGINS_DIR%\%HUTOOL_JAR%
)

if not defined FAIL_STEP (
    echo ----------------------------------------------------------------------------
    echo  Deployed override jars in plugins:
    dir /b "%PLUGINS_DIR%\override-apm-*.jar" 2>nul
    if errorlevel 1 echo       (none found)
    echo ----------------------------------------------------------------------------
    if exist "%AGENT_LOG_FILE%" (
        echo  Recent related log lines:
        powershell -NoProfile -Command "Get-Content -Path '%AGENT_LOG_FILE%' -Tail 200 | Select-String -Pattern 'override-apm-httpclient-4.x-plugin|override-apm-hutool-http-5.x-plugin'"
    ) else (
        echo  [WARN] SkyWalking log not found: %AGENT_LOG_FILE%
    )
)

echo ============================================================================

if "%DID_PUSHD%"=="1" popd

if defined FAIL_STEP exit /b 1
exit /b 0
