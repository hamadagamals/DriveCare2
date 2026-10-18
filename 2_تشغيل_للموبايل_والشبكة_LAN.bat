@echo off
chcp 65001 > nul
title DriveCare - LAN Server for Mobile
echo ===================================================
echo   تشغيل خادم DriveCare للموبايل والشبكة المحلية
echo ===================================================
echo.
cd /d "%~dp0"
echo لتوصيل هاتفك أو أي جهاز على الشبكة:
echo 1. تأكد أن الهاتف والكمبيوتر متصلان بنفس شبكة الـ Wi-Fi.
echo 2. افتح متصفح الهاتف واكتب IP الكمبيوتر مع المنفذ 3000 (مثال: http://192.168.1.5:3000/mobile).
echo.
python run_lan_server.py
pause
