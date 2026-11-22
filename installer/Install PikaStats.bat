@echo off
rem Double-click this file to install or start PikaStats.
rem It looks for Python 3.10+; if there is none it installs Python 3.12 for your Windows user with
rem winget (Microsoft's own installer), then starts launcher.py, which does the rest.
setlocal
title PikaStats Setup
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo   PIKASTATS SETUP
echo   checking for Python...

call :find_python
if defined PYEXE goto run

echo.
echo   Python 3.10 or newer was not found. The Microsoft Store version of Python cannot be used.
where winget >nul 2>&1
if errorlevel 1 goto manual
echo   Installing Python 3.12 for your Windows user with winget (Microsoft's installer)...
echo.
winget install --exact --id Python.Python.3.12 --scope user --accept-package-agreements --accept-source-agreements
call :find_python
if defined PYEXE goto run

:manual
echo.
echo   Please install Python 3.12 from python.org - the download page opens now.
echo   Then double-click "Install PikaStats.bat" again.
start "" "https://www.python.org/downloads/windows/"
goto fail

:run
echo   [+] Python: %PYEXE%
set "PYTHONIOENCODING=utf-8"
"%PYEXE%" "%~dp0launcher.py" --setup %*
if errorlevel 1 goto fail
exit /b 0

:fail
echo.
echo Setup stopped. Read the message above, then press any key to close.
pause >nul
exit /b 1

rem ---------------------------------------------------------------- helpers

:find_python
set "PYEXE="
call :try "%LOCALAPPDATA%\PikaStats\runtime\python\python.exe"
for %%V in (313 312 311 310) do if not defined PYEXE call :try "%LOCALAPPDATA%\Programs\Python\Python%%V\python.exe"
for %%V in (313 312 311 310) do if not defined PYEXE call :try "%ProgramFiles%\Python%%V\python.exe"
for %%V in (3 3.13 3.12 3.11 3.10) do if not defined PYEXE call :try_py %%V
exit /b 0

:try_py
where py >nul 2>&1
if errorlevel 1 exit /b 0
for /f "usebackq delims=" %%P in (`py -%1 -c "import sys; print(sys.executable)" 2^>nul`) do call :try "%%P"
exit /b 0

:try
rem 64-bit Python 3.10+, and not the Microsoft Store build (it hides files in a private folder).
if not exist "%~1" exit /b 0
"%~1" -c "import sys; sys.exit(0 if sys.version_info >= (3, 10) and sys.maxsize > 2**32 and 'WindowsApps' not in sys.executable else 1)" >nul 2>&1
if not errorlevel 1 set "PYEXE=%~1"
exit /b 0
