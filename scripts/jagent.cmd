@echo off
setlocal
set /p release=<"%~dp0..\current"
set "app=%~dp0..\releases\%release%"
if not defined JAGENT_STATE_DIR set "JAGENT_STATE_DIR=%USERPROFILE%\.jagent"
if "%~1"=="runner" goto runner
if "%~1"=="pair" goto runner
if "%~1"=="open" goto runner
if "%~1"=="server" goto full
if "%~1"=="" goto full
if "%~1"=="--help" goto help
if "%~1"=="help" goto help
echo Unknown command. Run jagent --help. 1>&2
exit /b 2
:runner
java -jar "%app%\runner\runner.jar" %*
exit /b %ERRORLEVEL%
:full
if not exist "%app%\distribution.json" (
  echo Install the full application for this command. Use jagent runner with the Runner package. 1>&2
  exit /b 1
)
node "%app%\scripts\global.mjs" %*
exit /b %ERRORLEVEL%
:help
if exist "%app%\distribution.json" echo jagent                   Start locally or reopen the Console
if exist "%app%\distribution.json" echo jagent server            Start the Harness and Console
echo jagent runner            Start the Coding Runner
echo jagent open [--link]     Open the Console or print a single-use link
echo jagent pair CODE         Approve a browser from its Runner machine
