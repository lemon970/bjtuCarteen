@echo off
chcp 65001 >nul
pushd "%~dp0"
title 餐厅仿真系统 - 启动器

echo ==========================================
echo   北交大餐厅仿真系统 - 一键启动器
echo ==========================================
echo.

REM === 1. 检查 Java ===
where java >nul 2>&1
if errorlevel 1 (
    echo [错误] 未检测到 Java 运行时。
    echo.
    echo 本系统需要 JDK 17 及以上版本。
    echo 下载: https://adoptium.net/temurin/releases/
    echo 安装时请勾选 "Set JAVA_HOME variable" 与 "Add to PATH"。
    echo.
    pause
    popd
    exit /b 1
)

REM === 2. 检查 Java 主版本 >= 17 ===
set JAVA_VER_RAW=
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VER_RAW=%%v
    goto :got_ver
)
:got_ver
set JAVA_VER=%JAVA_VER_RAW:"=%
for /f "tokens=1,2 delims=." %%a in ("%JAVA_VER%") do (
    set JAVA_MAJOR=%%a
    set JAVA_MINOR=%%b
)
if "%JAVA_MAJOR%"=="1" set JAVA_MAJOR=%JAVA_MINOR%
if %JAVA_MAJOR% LSS 17 (
    echo [错误] Java 版本 %JAVA_VER_RAW% 过低,需要 17 及以上。
    echo 请升级 JDK: https://adoptium.net/temurin/releases/
    pause
    popd
    exit /b 1
)
echo [OK] Java %JAVA_VER_RAW% 已就绪 ^(主版本 %JAVA_MAJOR%^)

REM === 3. 检查 jar ===
set JAR_FILE=
for %%f in (target\simulation-*-exec.jar) do set JAR_FILE=%%f
if "%JAR_FILE%"=="" (
    echo [错误] 未找到 target\simulation-*-exec.jar。
    echo 开发者请先双击 build.bat 构建,或联系开发者获取预构建包。
    pause
    popd
    exit /b 2
)
echo [OK] 已找到 %JAR_FILE%

REM === 4. 检查 8080 端口 ===
curl -s -o nul -w "%%{http_code}" --max-time 2 http://localhost:8080/api/simulation/scenarios > "%TEMP%\bjtu_port_check.txt" 2>nul
set /p PORT_CODE=<"%TEMP%\bjtu_port_check.txt"
del "%TEMP%\bjtu_port_check.txt" 2>nul
if "%PORT_CODE%"=="200" (
    echo [错误] 8080 端口已被占用,可能仿真服务已在运行。
    echo 请关闭旧实例后重试。如需排查,运行: netstat -ano ^| findstr :8080
    pause
    popd
    exit /b 3
)
echo [OK] 8080 端口可用

REM === 5. 启动 backend ===
echo.
echo 正在启动后端服务 ^(新窗口^)...
start "bjtu-canteen-simulator-backend" cmd /c "java -jar %JAR_FILE%"

REM === 6. 端口轮询最多 60 秒 ===
echo 等待后端就绪...
set /a WAIT_COUNT=0
:wait_loop
timeout /t 1 /nobreak >nul
set /a WAIT_COUNT+=1
curl -s -o nul -w "%%{http_code}" --max-time 1 http://localhost:8080/api/simulation/scenarios > "%TEMP%\bjtu_port_check.txt" 2>nul
set /p PORT_CODE=<"%TEMP%\bjtu_port_check.txt"
del "%TEMP%\bjtu_port_check.txt" 2>nul
if "%PORT_CODE%"=="200" goto :ready
if %WAIT_COUNT% GEQ 60 (
    echo.
    echo [错误] 后端启动超时 ^(60 秒^)。请查看 backend 控制台窗口的日志排查问题。
    pause
    popd
    exit /b 4
)
goto :wait_loop

:ready
echo [OK] 后端已就绪,用时 %WAIT_COUNT% 秒

REM === 7. 打开浏览器 ===
echo.
echo 正在打开浏览器...
start "" http://localhost:8080/

REM === 8. 等用户关停 ===
echo.
echo ==========================================
echo   仿真服务已启动,浏览器已打开。
echo   按任意键停止仿真服务并退出...
echo ==========================================
pause >nul

REM === 9. 停止 backend ===
echo.
echo 正在停止仿真服务...
taskkill /FI "WINDOWTITLE eq bjtu-canteen-simulator-backend*" /T /F >nul 2>&1
echo 已停止。
timeout /t 2 /nobreak >nul
popd
exit /b 0
