@echo off
chcp 936 >nul
setlocal
set "HERE=%~dp0"
set "A1=%~1"
set "A2=%~2"

if "%A1%"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%打包.ps1"
    goto end
)
if "%A1:~0,1%"=="-" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%打包.ps1" %*
    goto end
)

set EXTRA=
if /i "%A2%"=="debug"   set EXTRA=%EXTRA% -Variant debug
if /i "%A2%"=="install" set EXTRA=%EXTRA% -Install
if not "%A2%"=="" if "%A2:~0,1%"=="-" set EXTRA=%EXTRA% %2 %3 %4

powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%打包.ps1" -ProjectPath "%A1%" %EXTRA%

:end
echo.
echo   打包流程结束，按任意键关闭窗口...
pause >nul