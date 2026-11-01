@echo off
rem Windows (requires Java 21 on PATH)
if "%INTIMED_HOME%"=="" set INTIMED_HOME=%USERPROFILE%\IntiMed-Demo-Data
java -Xmx1g -jar "%~dp0app\intimed-platform.jar" --spring.profiles.active=demo
pause
