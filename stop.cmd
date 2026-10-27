@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-windows.ps1" -Stop
if errorlevel 1 (
  echo.
  echo FixFlow services could not stop. Read the error above.
  pause
  exit /b 1
)
