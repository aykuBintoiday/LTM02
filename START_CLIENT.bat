@echo off
chcp 65001 >nul
cd /d "%~dp0"
where java >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Chua tim thay Java. Hay cai JDK 17 tro len.
  pause
  exit /b 1
)
java -jar SmartClassroom-Client.jar
if errorlevel 1 pause
