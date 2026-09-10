@echo off
where java >nul 2>nul
if errorlevel 1 (
    echo CGen requires Java 17 or newer on PATH. 1>&2
    exit /b 1
)

java -jar "%~dp0cgen.jar" %*
exit /b %ERRORLEVEL%
