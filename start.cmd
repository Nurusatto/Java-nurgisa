@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-windows.ps1" %*
if errorlevel 1 (
  echo.
  echo FixFlow could not start. Read the error above.
  pause
  exit /b 1
)
