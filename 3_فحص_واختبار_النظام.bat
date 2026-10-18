@echo off
chcp 65001 > nul
title DriveCare - Unit & Security Tests
echo ===================================================
echo     تشغيل اختبارات الأمان والتحقق من النظام
echo ===================================================
echo.
cd /d "%~dp0"
python -m unittest discover -s tests -v
echo.
pause
