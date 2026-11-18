@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-local.ps1" %*
if errorlevel 1 (
    echo Local test failed. See the error above.
    pause
    exit /b 1
)
