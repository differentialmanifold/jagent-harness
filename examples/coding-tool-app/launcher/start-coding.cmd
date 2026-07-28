@echo off
setlocal

title JAgentHarness Coding

where node.exe >nul 2>nul
if errorlevel 1 (
  echo.
  echo Error: Node.js ^^20.19.0 or ^>=22.12.0 is required.
  pause
  exit /b 1
)

node.exe "%~dp0start-coding.mjs"
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" if not "%EXIT_CODE%"=="129" if not "%EXIT_CODE%"=="130" if not "%EXIT_CODE%"=="143" (
  echo.
  echo JAgentHarness coding launcher exited with code %EXIT_CODE%.
  pause
)

exit /b %EXIT_CODE%
