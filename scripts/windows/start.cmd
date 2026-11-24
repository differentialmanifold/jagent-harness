@echo off
setlocal
node "%~dp0..\start.mjs" local
set "status=%errorlevel%"
if not "%status%"=="0" pause
exit /b %status%
