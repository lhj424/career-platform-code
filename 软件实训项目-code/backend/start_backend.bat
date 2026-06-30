@echo off
chcp 65001 > nul

REM 从 .env 文件加载环境变量（跳过 # 注释行和空行）
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%~dp0..\.env") do (
    if not "%%a"=="" set "%%a=%%b"
)
"C:\Users\Lenovol\.jdks\corretto-17.0.13\bin\java.exe" -jar target\career-platform-1.0.0.jar --spring.profiles.active=dev > backend.log 2>&1
