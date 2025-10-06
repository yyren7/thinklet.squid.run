@echo off
REM =============================================================================
REM Thinklet Streaming Environment - Auto-Elevated Start
REM 
REM This batch file will automatically request administrator privileges
REM and start all required services for the streaming environment.
REM 
REM DOUBLE-CLICK THIS FILE TO START
REM =============================================================================

REM Check for administrator privileges
net session >nul 2>&1
if %errorLevel% == 0 (
    goto :run_script
)

REM If not running as admin, request elevation
echo Requesting administrator privileges...
powershell -Command "Start-Process '%~f0' -Verb RunAs"
exit /b

:run_script
REM Change to script directory
cd /d "%~dp0"

title Thinklet Streaming Environment Startup

echo.
echo ============================================================
echo  Thinklet Streaming Environment - Startup
echo ============================================================
echo.
echo Running with administrator privileges...
echo Current Directory: %CD%
echo.

REM Execute the PowerShell script
PowerShell.exe -ExecutionPolicy Bypass -File "%~dp0start-streaming-environment.ps1"

REM Check if the script executed successfully
if %errorLevel% == 0 (
    echo.
    echo ============================================================
    echo  All Services Started Successfully!
    echo ============================================================
    echo.
    echo You can now:
    echo   - Connect Android devices to start streaming
    echo   - Open web browser to view the streams
    echo.
) else (
    echo.
    echo ============================================================
    echo  Startup Failed - Please Check Error Messages Above
    echo ============================================================
    echo.
)

REM Keep the window open
echo Press any key to close this window...
pause >nul






