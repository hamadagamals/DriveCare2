@echo off
chcp 65001 > nul
title DriveCare - Local Server
echo ===================================================
echo     تشغيل خادم DriveCare - قراءة حساسات السيارة
echo ===================================================
echo.
cd /d "%~dp0"
echo [1/2] جاري تجهيز الخادم المحلي...
echo [2/2] سيتم فتح المتصفح تلقائياً على واجهة التطبيق
start http://localhost:3000/mobile
python server.py
pause
