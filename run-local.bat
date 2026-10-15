@echo off
setlocal enabledelayedexpansion

set "PLATFORM=win"
set "CLASSPATH=included"
set "CLIENT_COUNT=3"

echo ============================================
echo  Java ICE Adapter - Local Run (Windows)
echo ============================================

REM --- Build ---
echo.
echo [1/3] Building JARs...
call gradlew.bat -PjavafxPlatform=%PLATFORM% -PwebrtcPlatform=%PLATFORM% -PjavafxClasspath=%CLASSPATH% :client:shadowJar :server:shadowJar :ice-adapter:shadowJar
if errorlevel 1 (
    echo ERROR: Build failed!
    exit /b 1
)

REM --- Copy JARs to root ---
echo.
echo [2/3] Copying JARs to project root...

set "ICE_JAR="
for %%f in (ice-adapter\build\libs\faf-ice-adapter-*.jar) do (
    copy /Y "%%f" "faf-ice-adapter.jar" >nul
    set "ICE_JAR=%%f"
)
if "%ICE_JAR%"=="" (
    echo ERROR: ice-adapter shadow JAR not found!
    exit /b 1
)

set "CLIENT_JAR="
for %%f in (client\build\libs\client-*-all.jar) do (
    set "CLIENT_JAR=%%f"
)
if "%CLIENT_JAR%"=="" (
    echo ERROR: client shadow JAR not found!
    exit /b 1
)
copy /Y "!CLIENT_JAR!" "test-client.jar" >nul

set "SERVER_JAR="
for %%f in (server\build\libs\server-*-all.jar) do (
    set "SERVER_JAR=%%f"
)
if "%SERVER_JAR%"=="" (
    echo ERROR: server shadow JAR not found!
    exit /b 1
)
copy /Y "!SERVER_JAR!" "test-server.jar" >nul

echo JARs copied successfully.

REM --- Start Server ---
echo.
echo [3/3] Starting test server...
start "TestServer" cmd /k "java -jar test-server.jar"
timeout /t 3 /nobreak >nul

REM --- Start Clients ---
echo Starting %CLIENT_COUNT% test clients...
for /L %%i in (1,1,%CLIENT_COUNT%) do (
    echo   Starting client test-%%i ...
    start "TestClient-%%i" cmd /k "java -jar test-client.jar --name=test%%i"
)

echo.
echo ============================================
echo  All processes started!
echo ============================================
echo.
echo To stop: close the console windows.
echo.
pause
