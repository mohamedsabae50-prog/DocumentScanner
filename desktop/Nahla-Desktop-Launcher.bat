@echo off
setlocal
set "APP=%~dp0index.html"
if not exist "%APP%" (
    echo Nahla Desktop was not found at "%APP%".
    pause
    exit /b 1
)
if exist "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" (
    start "" "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" --app="file:///%APP%"
    exit /b 0
)
if exist "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" (
    start "" "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" --app="file:///%APP%"
    exit /b 0
)
start "" "%APP%"
endlocal
