@echo off
rem Usage:
rem   run.bat            -> open D:\exce (production)
rem   run.bat test       -> copy TestTemp to build\TestTemp-work and open the copy (original untouched)
rem   run.bat <folder>   -> open <folder>
setlocal
cd /d "%~dp0"
if not exist "dist\IniEditor.jar" (
  call build.bat
  if errorlevel 1 exit /b 1
)
set "ROOT=%~1"
if "%ROOT%"=="" set "ROOT=D:\exce"
if /i "%ROOT%"=="test" (
  if not exist "TestTemp" (
    echo [FAIL] TestTemp folder not found
    exit /b 1
  )
  if exist "build\TestTemp-work" rmdir /s /q "build\TestTemp-work"
  xcopy "TestTemp" "build\TestTemp-work\" /E /I /Y /Q >nul
  set "ROOT=%~dp0build\TestTemp-work"
)
echo [RUN] %ROOT%
start "" javaw -jar "dist\IniEditor.jar" "%ROOT%"
