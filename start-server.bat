@echo off
rem voxnote server one-click launcher
rem Usage: start-server.bat [--port 8787] [other run.py args]
rem Data dir is resolved from CWD (./data), so always cd into server\ first.
cd /d "%~dp0server"

if not exist "venv\Scripts\python.exe" (
    echo [voxnote] venv not found: server\venv\Scripts\python.exe
    echo [voxnote] Create it first, e.g.:
    echo   cd server ^&^& python -m venv venv ^&^& venv\Scripts\pip install -r requirements.txt
    pause
    exit /b 1
)

echo [voxnote] starting server (data: %CD%\data)
"venv\Scripts\python.exe" run.py %*

rem Keep the window open if the server exits or crashes, so the error is readable.
echo [voxnote] server exited.
pause
