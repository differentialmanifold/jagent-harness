@echo off
pushd "%~dp0.."
if errorlevel 1 exit /b 1
call node "scripts/start-frontend.mjs" %*
set "launcher_exit=%errorlevel%"
popd
if not "%launcher_exit%"=="0" pause
exit /b %launcher_exit%
