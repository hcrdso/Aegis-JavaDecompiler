@echo off
setlocal
cd /d "%~dp0"
if not exist build\Aegis.jar (
  call build.bat
  if errorlevel 1 exit /b 1
)
java -jar build\Aegis.jar %*
endlocal
