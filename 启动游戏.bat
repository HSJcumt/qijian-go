@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" client
if errorlevel 1 (
    echo Client launch failed. See the error above.
    pause
    exit /b 1
)
