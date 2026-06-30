@echo off
chcp 65001 > nul
title AI求职平台 - 后端服务

REM 从 .env 文件加载环境变量（跳过 # 注释行和空行）
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%~dp0.env") do (
    if not "%%a"=="" set "%%a=%%b"
)

echo ============================================
echo   正在启动后端服务 (端口 8080)...
echo ============================================
echo.

java -jar "%~dp0backend\target\career-platform-1.0.0.jar" --spring.profiles.active=dev
pause
