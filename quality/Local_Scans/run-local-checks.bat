@echo off
setlocal

REM ==========================================================
REM Local Quality Gate — Windows Batch Launcher
REM ----------------------------------------------------------
REM Entry point for the advisory Local Quality Gate.
REM
REM Uses Git Bash to execute the main shell-based scanning
REM workflow, including report generation and packaging.
REM
REM Usage, prerequisites, and troubleshooting:
REM   quality/Local_Scans/README.md
REM
REM Cross-project installation:
REM   quality/PORTABILITY.md
REM
REM Windows execution has not yet been fully verified.
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
    echo Local Quality Gate PASSED.
) else (
    echo Local Quality Gate FAILED.
    echo Exit code: %EXIT_CODE%
)

exit /b %EXIT_CODE%
