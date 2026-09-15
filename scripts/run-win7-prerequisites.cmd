@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%check-win7-prerequisites.ps1" -ReportPath "%SCRIPT_DIR%..\artifacts\e2e\win7-prerequisites.json"
echo.
if errorlevel 2 goto tool_error
if errorlevel 1 goto prerequisite_failure
echo Win7 prerequisite check passed.
set "EXIT_CODE=0"
goto done

:prerequisite_failure
echo Win7 prerequisite check completed, but one or more prerequisites failed.
set "EXIT_CODE=1"
goto done

:tool_error
echo Win7 prerequisite check could not complete. Review the error above.
set "EXIT_CODE=2"

:done
pause
exit /b %EXIT_CODE%
