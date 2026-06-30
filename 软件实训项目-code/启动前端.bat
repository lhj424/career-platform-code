@echo off
chcp 65001 > nul
title AI求职平台 - 前端服务

cd /d "%~dp0frontend"

echo ============================================
echo   正在检查依赖...
echo ============================================
if not exist "node_modules" (
    echo 首次运行，正在安装前端依赖...
    call npm install --legacy-peer-deps
)

echo.
echo ============================================
echo   正在启动前端服务 (端口 5173)...
echo ============================================
echo.
npm run dev
pause
