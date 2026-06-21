@echo off
setlocal
set ROOT=%~dp0
cd /d "%ROOT%"
if "%MAVEN_REPOSITORY%"=="" set MAVEN_REPOSITORY=%TEMP%\7e-wfgpt-m2
if not exist "%MAVEN_REPOSITORY%" mkdir "%MAVEN_REPOSITORY%"

mvn -Dmaven.repo.local="%MAVEN_REPOSITORY%" -pl backend -am package -DskipTests
if errorlevel 1 exit /b 1

start "Key Handover Local API" /b java -Dworkflow.http.port=8080 -cp "backend\target\classes;domain\target\classes;contracts\target\classes" com.sevenewf.workflow.backend.BackendApplication
echo Local Key Handover demo: http://localhost:5173
echo Synthetic local data only. Close this window to stop the frontend.
npm run dev --workspace frontend -- --host 127.0.0.1
