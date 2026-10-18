$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$gradleBat = "C:\Users\Eng Hamada\.gradle\wrapper\dists\gradle-8.14-all\c2qonpi39x1mddn7hk5gh9iqj\gradle-8.14\bin\gradle.bat"
$projDir = "F:\projects\DriveCare\android-native"
& $gradleBat -p $projDir :app:assembleDebug --console=plain
$apkOut = "$projDir\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apkOut) {
    Copy-Item $apkOut "F:\projects\DriveCare\DriveCare_Live_OBD.apk" -Force
    Copy-Item $apkOut "F:\projects\DriveCare_Live_OBD.apk" -Force
    Write-Host "Updated APK ready at F:\projects\DriveCare_Live_OBD.apk" -ForegroundColor Green
}
