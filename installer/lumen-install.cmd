@echo off
rem SPDX-License-Identifier: Apache-2.0
rem Lumen OS installer for the XGIMI Z9X: Windows launcher (BETA). See lumen-install.ps1 and the guide.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0lumen-install.ps1" %*
