@echo off
chcp 65001 >nul
pushd "%~dp0"
echo === 开始构建 (mvnw.cmd package -DskipTests) ===
call "%~dp0setup-java.bat"
if errorlevel 1 (
    echo.
    echo Java 环境准备失败。
    pause
    popd
    exit /b 1
)
echo 首次构建会下载 Maven 3.9.14 + node 20.11.1 + npm 依赖,可能需要 3-5 分钟,请耐心等待
echo.
call "%~dp0mvnw.cmd" package -DskipTests
if errorlevel 1 (
    echo.
    echo 构建失败。请查看上方 Maven 输出排查问题。
    pause
    popd
    exit /b 1
)
echo.
echo === 构建完成 ===
dir target\simulation-*-exec.jar | findstr exec
echo.
echo 现在可以双击 run.bat 启动系统。
pause
popd
