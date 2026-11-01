@echo off
setlocal
set "APP_HOME=%~dp0"
if not exist "%APP_HOME%bin\jira-analytics-kotlin.bat" (
  echo Distribution is incomplete: bin\jira-analytics-kotlin.bat is missing. 1>&2
  exit /b 1
)
cd /d "%APP_HOME%"
call "%APP_HOME%bin\jira-analytics-kotlin.bat" "%APP_HOME%config\app.properties" %*
