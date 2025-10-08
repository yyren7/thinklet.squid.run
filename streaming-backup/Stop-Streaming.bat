@echo off
echo ============================================================
echo  Stopping Thinklet Streaming Environment
echo ============================================================

REM Stop Node.js server
echo.
echo --> Stopping Node.js server (port 8000)...
taskkill /F /IM node.exe >nul 2>&1
if %errorLevel% == 0 (
    echo      - Node.js server stopped.
) else (
    echo      - No Node.js server was running.
)

REM Stop Docker container
echo.
echo --> Stopping SRS Docker container...
wsl -e bash -c "docker stop srs-server >/dev/null 2>&1 && docker rm srs-server >/dev/null 2>&1"
if %errorLevel% == 0 (
    echo      - SRS Docker container stopped and removed.
) else (
    echo      - SRS Docker container not found or already stopped.
)

echo.
echo ============================================================
echo  All services stopped.
echo ============================================================
echo.
pause



