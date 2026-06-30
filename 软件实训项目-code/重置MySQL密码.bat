@echo off
chcp 65001 > nul
title MySQL 密码重置 (目标: 123456)
echo ============================================
echo   MySQL Root 密码重置工具
echo   目标密码: 123456
echo ============================================
echo.

set MYSQL_BIN=D:\Mysql\bin

echo [1/4] 停止 MySQL 服务...
net stop MySQL80
echo.

echo [2/4] 以安全模式启动 MySQL (skip-grant-tables)...
start "" "%MYSQL_BIN%\mysqld" --skip-grant-tables --skip-networking --shared-memory
echo 等待 MySQL 启动 (10秒)...
timeout /t 10 /nobreak > nul
echo.

echo [3/4] 重置 root 密码...
echo 正在连接并重置密码...

REM 方法1: 用 ALTER USER
"%MYSQL_BIN%\mysql" -u root --protocol=PIPE -e "FLUSH PRIVILEGES; ALTER USER 'root'@'localhost' IDENTIFIED BY '123456'; FLUSH PRIVILEGES;" 2>nul
if %ERRORLEVEL% equ 0 goto :success

REM 方法2: 直接更新 authentication_string
echo 方法1失败，尝试方法2...
"%MYSQL_BIN%\mysql" -u root --protocol=PIPE -e "FLUSH PRIVILEGES; ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY '123456'; FLUSH PRIVILEGES;" 2>nul
if %ERRORLEVEL% equ 0 goto :success

REM 方法3: 使用 SET PASSWORD 旧语法
echo 方法2失败，尝试方法3...
"%MYSQL_BIN%\mysql" -u root --protocol=PIPE -e "SET PASSWORD FOR 'root'@'localhost' = '123456'; FLUSH PRIVILEGES;" 2>nul
if %ERRORLEVEL% equ 0 goto :success

echo 警告：所有自动方法均失败，请手动重置。
goto :restart

:success
echo 密码重置成功！
echo 验证: 测试连接...

:restart
echo.
echo [4/4] 重启 MySQL 服务...
taskkill /f /im mysqld.exe 2>nul
timeout /t 3 /nobreak > nul
net start MySQL80
echo.

echo ============================================
echo   操作完成。新密码: 123456
echo ============================================
echo.

REM 验证密码
echo 正在验证...
timeout /t 3 /nobreak > nul
"%MYSQL_BIN%\mysql" -u root -p123456 -e "SELECT 'PASSWORD_OK' AS result;" 2>nul
if %ERRORLEVEL% equ 0 (
    echo 验证成功: 密码 123456 可用！
) else (
    echo 验证失败: 密码可能未正确设置。
)

echo.
echo 按任意键退出...
pause > nul
