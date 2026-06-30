@echo off
setlocal
cd /d "d:\企业实训作业\codebuddy\Training product(1)\Training product"

REM Extract git credential
(echo protocol=https& echo host=github.com& echo.) | git credential fill > "%TEMP%\gh_cred.txt" 2>nul
for /f "tokens=2 delims==" %%a in ('findstr "password=" "%TEMP%\gh_cred.txt"') do set GH_TOKEN=%%a
del "%TEMP%\gh_cred.txt" 2>nul

if "%GH_TOKEN%"=="" (
    echo ERROR: Cannot get GitHub token
    exit /b 1
)
echo Token obtained successfully

REM Login with token
echo %GH_TOKEN% | "C:\Program Files\GitHub CLI\gh.exe" auth login --with-token --hostname github.com 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo Login failed
    exit /b 1
)
echo Login successful

REM Delete repository
"C:\Program Files\GitHub CLI\gh.exe" repo delete lkiq/Test --confirm 2>&1
echo Delete exit code: %ERRORLEVEL%

REM Create new empty repository
"C:\Program Files\GitHub CLI\gh.exe" repo create lkiq/Test --public --description "AI智能求职辅导平台" 2>&1
echo Create exit code: %ERRORLEVEL%

echo Done!
