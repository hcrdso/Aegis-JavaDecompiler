@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
  echo [Aegis] javac was not found in PATH.
  echo Install a JDK 21+ and reopen Command Prompt.
  exit /b 1
)
where jar >nul 2>nul
if errorlevel 1 (
  echo [Aegis] jar was not found in PATH.
  echo Make sure you installed a full JDK, not only a JRE.
  exit /b 1
)

if exist build rmdir /s /q build
mkdir build\classes

set "SOURCES=build\sources.txt"
if exist "%SOURCES%" del "%SOURCES%"
for /r "src\main\java" %%F in (*.java) do echo %%F>>"%SOURCES%"

echo [Aegis] Compiling native core with javac...
javac --release 21 -encoding UTF-8 -d build\classes @"%SOURCES%"
if errorlevel 1 exit /b 1

>build\manifest.mf echo Manifest-Version: 1.0
>>build\manifest.mf echo Main-Class: dev.aegis.Aegis
>>build\manifest.mf echo Implementation-Title: Aegis Retro Intelligence
>>build\manifest.mf echo Implementation-Version: 0.4.0-retro-intelligence
>>build\manifest.mf echo.

echo [Aegis] Creating build\Aegis.jar...
jar --create --file build\Aegis.jar --manifest build\manifest.mf -C build\classes .
if errorlevel 1 exit /b 1

echo.
echo [Aegis] BUILD SUCCESS
for %%A in (build\Aegis.jar) do echo [Aegis] %%~fA  ^(%%~zA bytes^)
endlocal
