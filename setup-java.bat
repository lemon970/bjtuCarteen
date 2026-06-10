@echo off
set "PROJECT_DIR=%~dp0"
set "BUNDLED_JDK_DIR=%PROJECT_DIR%java\jdk"
set "BUNDLED_JDK_ZIP=%PROJECT_DIR%java\temurin-jdk17-windows-x64.zip"

if exist "%BUNDLED_JDK_DIR%\bin\java.exe" goto useBundledJdk

if exist "%BUNDLED_JDK_ZIP%" (
    echo [INFO] Extracting bundled JDK...
    powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%setup-java.ps1" -Archive "%BUNDLED_JDK_ZIP%" -Destination "%BUNDLED_JDK_DIR%"
    if errorlevel 1 (
        echo [ERROR] Failed to prepare bundled JDK.
        exit /b 1
    )
    if exist "%BUNDLED_JDK_DIR%\bin\java.exe" goto useBundledJdk
    echo [ERROR] Prepared bundled JDK does not contain bin\java.exe.
    exit /b 1
)

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto useConfiguredJdk

if not defined JAVA_HOME for /f "delims=" %%J in ('where javac.exe 2^>nul') do if not defined JAVA_HOME set "JAVA_HOME=%%~dpJ.."
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto useConfiguredJdk

echo [ERROR] JDK not found.
echo Install JDK 17, set JAVA_HOME, add javac.exe to PATH, or include:
echo %BUNDLED_JDK_ZIP%
exit /b 1

:useBundledJdk
set "JAVA_HOME=%BUNDLED_JDK_DIR%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
exit /b 0

:useConfiguredJdk
set "PATH=%JAVA_HOME%\bin;%PATH%"
exit /b 0
