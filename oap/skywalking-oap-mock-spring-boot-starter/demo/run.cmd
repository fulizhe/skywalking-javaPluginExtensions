@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo.
echo === mock-oap-demo one-click start ===
echo.

rem ---------- 1. locate SkyWalking agent ----------
set "AGENT=%~1"
if not defined AGENT set "AGENT=%SKYWALKING_AGENT_JAR%"
if not defined AGENT if exist "skywalking-agent\skywalking-agent.jar" set "AGENT=%CD%\skywalking-agent\skywalking-agent.jar"
if not defined AGENT if exist "..\skywalking-agent\skywalking-agent.jar" set "AGENT=%CD%\..\skywalking-agent\skywalking-agent.jar"
if not defined AGENT if exist "%USERPROFILE%\skywalking-agent\skywalking-agent.jar" set "AGENT=%USERPROFILE%\skywalking-agent\skywalking-agent.jar"
if not defined AGENT if exist "C:\skywalking-agent\skywalking-agent.jar" set "AGENT=C:\skywalking-agent\skywalking-agent.jar"
if defined AGENT if exist "%AGENT%\skywalking-agent.jar" set "AGENT=%AGENT%\skywalking-agent.jar"
if not defined AGENT set /p "AGENT=SkyWalking agent path (jar or its directory, leave empty for help): "
if defined AGENT if exist "%AGENT%\skywalking-agent.jar" set "AGENT=%AGENT%\skywalking-agent.jar"

if not defined AGENT (
    echo SkyWalking agent not found. Extract the agent, then pick one:
    echo   1. run.cmd D:\path\to\skywalking-agent\skywalking-agent.jar
    echo   2. set SKYWALKING_AGENT_JAR=D:\path\to\skywalking-agent\skywalking-agent.jar
    echo   3. put the agent under demo\skywalking-agent\
    echo.
    echo Download: https://skywalking.apache.org/downloads/
    pause
    exit /b 1
)

if not exist "%AGENT%" (
    echo [ERROR] agent not found: %AGENT%
    pause
    exit /b 1
)

rem ---------- 2. build when needed ----------
set "JAR=target\mock-oap-demo-2.0.0.jar"

rem force rebuild after source changes: set MOCK_OAP_REBUILD=1
set "FORCE_BUILD="
if /i "%MOCK_OAP_REBUILD%"=="1" set "FORCE_BUILD=1"
if not exist "%JAR%" set "FORCE_BUILD=1"

if defined FORCE_BUILD (
    where mvn >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] mvn not found. Install Maven, or build manually:
        echo     cd ..
        echo     mvn -B install -DskipTests
        echo     cd demo
        echo     mvn -B package
        pause
        exit /b 1
    )
    echo [1/2] building starter ...
    pushd ..
    call mvn -B -q -DskipTests install
    if errorlevel 1 (
        popd
        echo [ERROR] starter build failed.
        pause
        exit /b 1
    )
    popd
    echo [2/2] building demo ...
    call mvn -B -q -DskipTests package
    if errorlevel 1 (
        echo [ERROR] demo build failed.
        pause
        exit /b 1
    )
)

if not exist "%JAR%" (
    echo [ERROR] %JAR% not found.
    pause
    exit /b 1
)

rem ---------- 3. start ----------
set "SW_AGENT_NAME=mock-oap-demo"
set "SW_AGENT_COLLECTOR_BACKEND_SERVICES=127.0.0.1:11800"

echo.
echo agent : %AGENT%
echo jar   : %JAR%
echo page  : http://localhost:9600/   (demo portal)
echo tip   : wait ~30s after startup, then click "generate chain"
echo stop  : Ctrl+C
echo.

java -javaagent:"%AGENT%" -jar "%JAR%"

echo.
echo Application stopped.
pause
