@echo off
setlocal
cd /d "%~dp0"
echo ALBO Connect Neustart - Servercode mit Zeiterfassungs-Fix
echo Suche den Prozess auf Port 8787 ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$listener = Get-NetTCPConnection -LocalPort 8787 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1; if ($listener) { Write-Host ('Beende bisherigen Serverprozess PID ' + $listener.OwningProcess); Stop-Process -Id $listener.OwningProcess -Force } else { Write-Host 'Kein alter Serverprozess gefunden.' }"
timeout /t 2 /nobreak >nul
set "ALBO_NODE=node"
where node >nul 2>&1
if errorlevel 1 (
  if exist "%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe" (
    set "ALBO_NODE=%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"
  ) else (
    echo Node.js konnte nicht gefunden werden.
    pause
    exit /b 1
  )
)
echo Starte Server aus: %CD%
echo Browseradresse: http://localhost:8787
"%ALBO_NODE%" "%~dp0server.mjs" --open
echo.
echo Der Server wurde beendet. Exitcode: %ERRORLEVEL%
pause