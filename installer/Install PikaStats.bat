@echo off
rem Double-click this file to install or start PikaStats. Nothing else is needed.
title PikaStats Setup
chcp 65001 >nul
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bootstrap.ps1" %*
if errorlevel 1 (
    echo.
    echo Setup stopped. Read the message above, then press any key to close.
    pause >nul
)
