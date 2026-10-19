@echo off
setlocal
cd /d "%~dp0"
if not exist "build\install\CodingAgentDemo\bin\CodingAgentDemo.bat" (
    echo Please run gradlew.bat installDist first.
    pause
    exit /b 1
)
call "build\install\CodingAgentDemo\bin\CodingAgentDemo.bat" %*
set "AGENT_EXIT_CODE=%ERRORLEVEL%"
if not "%AGENT_EXIT_CODE%"=="0" pause
exit /b %AGENT_EXIT_CODE%
