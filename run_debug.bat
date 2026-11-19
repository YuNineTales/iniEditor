@echo off
rem Same as run.bat but keeps the console open to show errors
setlocal
set "ROOT=%~1"
if "%ROOT%"=="" set "ROOT=D:\exce"
java -jar "%~dp0dist\IniEditor.jar" "%ROOT%"
pause
