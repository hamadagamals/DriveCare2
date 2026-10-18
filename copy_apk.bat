@echo off
set "SRC=F:\projects\DriveCare\android-native\app\build\outputs\apk\debug\app-debug.apk"
set "ROOT=F:\projects\DriveCare"

powershell -NoProfile -Command ^
  "$target = Get-ChildItem -Path 'F:\' -Filter '*odp2*' -Recurse -Directory | Select-Object -ExpandProperty FullName -First 1;" ^
  "Write-Host 'Target folder:' $target;" ^
  "Copy-Item '%SRC%' -Destination \"$target\DriveCare_Live_OBD.apk\" -Force;" ^
  "Copy-Item '%SRC%' -Destination '%ROOT%\DriveCare_Live_OBD.apk' -Force;" ^
  "Get-Item \"$target\DriveCare_Live_OBD.apk\" | Select-Object Name, Length, LastWriteTime;" ^
  "Write-Host 'Creating updated portable zip...';" ^
  "Compress-Archive -Path '%ROOT%\*' -DestinationPath \"$target\DriveCare_Portable_Ready.zip\" -Force;" ^
  "Write-Host 'ZIP successfully created:';" ^
  "Get-Item \"$target\DriveCare_Portable_Ready.zip\" | Select-Object Name, Length, LastWriteTime;"
