@echo off
rem Starts PhotoServer. Put a shortcut to this file in shell:startup to run on boot.
cd /d "%~dp0"
node src\index.js
pause
