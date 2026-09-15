@echo off
rem 双击前可在命令行传入来源数量与持续秒数，例如：
rem   run-win7-winforms-smoke.cmd 6 0
rem 默认按四路回归基线、按帧数取证。
setlocal
set SOURCE_COUNT=%~1
if "%SOURCE_COUNT%"=="" set SOURCE_COUNT=4
set DURATION_SECONDS=%~2
if "%DURATION_SECONDS%"=="" set DURATION_SECONDS=0
pushd "%~dp0.."
if errorlevel 1 goto :fail
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0test-winforms-person-detection.ps1" -RepoRoot "%CD%" -SourceCount %SOURCE_COUNT% -DurationSeconds %DURATION_SECONDS%
if errorlevel 1 goto :fail
echo.
echo Win7 WinForms smoke passed (sources=%SOURCE_COUNT%, durationSeconds=%DURATION_SECONDS%).
pause
popd
exit /b 0

:fail
echo.
echo Win7 WinForms smoke FAILED. Keep this window open and send a screenshot.
pause
popd
exit /b 1
