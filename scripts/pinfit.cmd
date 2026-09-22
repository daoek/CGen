@echo off
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    "%JAVA_HOME%\bin\java.exe" -jar "%~dp0pinfit.jar" %*
    exit /b %ERRORLEVEL%
)

where java >nul 2>nul
if errorlevel 1 (
    echo Pinfit requires Java 17 or newer on PATH. 1>&2
    exit /b 1
)

java -jar "%~dp0pinfit.jar" %*
exit /b %ERRORLEVEL%
