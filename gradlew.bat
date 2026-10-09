@rem Gradle startup script for Windows
@echo off
setlocal
set APP_HOME=%~dp0
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
java -jar "%WRAPPER_JAR%" %*
endlocal
