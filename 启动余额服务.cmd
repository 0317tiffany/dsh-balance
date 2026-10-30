@echo off
chcp 65001 >nul
title DSH Balance Server
cd /d "%~dp0"

echo.
echo   ===============================================
echo     DSH Balance - PC side service
echo   ===============================================
echo.

where node >nul 2>nul
if errorlevel 1 (
  echo   [x] Node.js not found.
  echo       Install the LTS build first: https://nodejs.org
  echo.
  pause
  exit /b 1
)

echo   If the phone cannot open the page, run the command below
echo   ONE TIME as Administrator (right-click - Run as administrator):
echo.
echo     netsh advfirewall firewall add rule name="DSH Balance" dir=in action=allow protocol=TCP localport=8787
echo.
echo   Keep this window open while you use the phone app.
echo   Press Ctrl+C to stop. The phone shows an offline snapshot
echo   of the last synced numbers while this PC is off.
echo.

node balance-server.js

echo.
echo   Service stopped.
pause
