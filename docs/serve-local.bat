@echo off
setlocal
cd /d "%~dp0"

echo.
echo  ISO8583Studio site - local preview
echo  Serving: %CD%
echo  Open:    http://127.0.0.1:8080/
echo  Press Ctrl+C to stop.
echo.

where python >nul 2>&1
if errorlevel 1 (
  echo ERROR: Python was not found on PATH.
  echo Install Python 3, then run this file again.
  pause
  exit /b 1
)

start "" "http://127.0.0.1:8080/"
python -m http.server 8080
pause
