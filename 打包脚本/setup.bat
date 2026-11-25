@echo off
chcp 936 >nul
setlocal
set "HERE=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%setup.ps1" %*
echo.
echo   安装流程结束，按任意键关闭窗口...
pause >nul