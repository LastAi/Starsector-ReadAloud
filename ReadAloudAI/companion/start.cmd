@echo off
rem Read Aloud companion app (Windows). Double-click to open the setup page.
rem The first run downloads uv and Python into runtime\ (about 50 MB); nothing is
rem installed system-wide. Arguments are passed to the app (see app\server.py --help).
setlocal
set "HERE=%~dp0"
set "RT=%HERE%runtime"
set "UV=%RT%\uv\uv.exe"
set "UV_VERSION=0.13.7"
if not exist "%RT%\uv" mkdir "%RT%\uv"

if not exist "%UV%" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%bootstrap.ps1" -Dest "%RT%\uv" -Version %UV_VERSION%
  if errorlevel 1 (
    echo.
    echo Could not download uv. Check your internet connection and try again.
    if /i not "%~1"=="--background" pause
    exit /b 1
  )
)

rem Keep everything uv does inside runtime\ and away from the user profile and registry.
set "UV_CACHE_DIR=%RT%\uv-cache"
set "UV_PYTHON_INSTALL_DIR=%RT%\python"
set "UV_PYTHON_CACHE_DIR=%RT%\uv-cache\python"
set "UV_PYTHON_BIN_DIR=%RT%\python\bin"
set "UV_TOOL_DIR=%RT%\uv-tools"
set "UV_PYTHON_INSTALL_REGISTRY=0"
set "UV_MANAGED_PYTHON=1"
set "UV_NO_CONFIG=1"
set "READALOUD_UV=%UV%"

if /i "%~1"=="--background" (
  "%UV%" run --no-project --python 3.12 "%HERE%app\server.py" %* > "%RT%\console.log" 2>&1
  exit /b
)
"%UV%" run --no-project --python 3.12 "%HERE%app\server.py" %*
if errorlevel 1 pause
