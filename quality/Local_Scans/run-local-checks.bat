@echo off
setlocal

REM ==========================================================
REM EPMS Local Quality Gate (BN1) - Windows Launcher
REM ----------------------------------------------------------
REM Location:
REM   quality\Local_Scans\run-local-checks.bat
REM
REM Requirements:
REM   Git for Windows (Git Bash)
REM   Java
REM   Python 3.10+
REM   Maven Wrapper prerequisites
REM   Network access for initial scanner downloads
REM
REM Runs the main shell-based quality gate through Git Bash.
REM ==========================================================

set "GIT_BASH="

REM Check common Git for Windows installation locations.
if exist "C:\Program Files\Git\bin\bash.exe" (
    set "GIT_BASH=C:\Program Files\Git\bin\bash.exe"
    goto :found
)

if exist "C:\Program Files (x86)\Git\bin\bash.exe" (
    set "GIT_BASH=C:\Program Files (x86)\Git\bin\bash.exe"
    goto :found
)

REM Try locating Git through PATH.
for /f "delims=" %%G in ('where git 2^>nul') do (
    for %%D in ("%%~dpG..") do (
        if exist "%%~fD\bin\bash.exe" (
            set "GIT_BASH=%%~fD\bin\bash.exe"
            goto :found
        )
    )
)

echo ERROR: Git Bash was not found.
echo Install Git for Windows and try again.
exit /b 2

:found
echo Using Git Bash: %GIT_BASH%

REM Locate the shell runner relative to this batch file.
set "SCRIPT_WIN=%~dp0run-local-checks.sh"

if not exist "%SCRIPT_WIN%" (
    echo ERROR: Quality gate shell script not found.
    exit /b 2
)

REM Convert the Windows script path to a Git Bash-compatible path.
for /f "delims=" %%P in ('"%GIT_BASH%" -c "cygpath -u '%SCRIPT_WIN%'"') do (
    set "SCRIPT_UNIX=%%P"
)

if not defined SCRIPT_UNIX (
    echo ERROR: Unable to convert the quality gate script path.
    exit /b 2
)

REM Execute the quality gate.
"%GIT_BASH%" -c "sh '%SCRIPT_UNIX%'"

set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
    echo EPMS Local Quality Gate PASSED.
) else (
    echo EPMS Local Quality Gate FAILED.
    echo Exit code: %EXIT_CODE%
)

exit /b %EXIT_CODE%
