@echo off
rem Build dist\IniEditor.jar (Java 8 target, no external libs)
setlocal
cd /d "%~dp0"
if exist build\classes rmdir /s /q build\classes
mkdir build\classes
if not exist dist mkdir dist
javac -encoding UTF-8 -source 1.8 -target 1.8 -d build\classes src\IniEditor.java
if errorlevel 1 goto fail
jar cfm dist\IniEditor.jar MANIFEST.MF -C build\classes .
if errorlevel 1 goto fail
echo [OK] dist\IniEditor.jar
exit /b 0
:fail
echo [FAIL] build failed
exit /b 1
