@echo off
echo Setting JAVA_HOME...
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
echo JAVA_HOME set to: %JAVA_HOME%

echo.
echo Building Android app...
cd /d "%~dp0"
call gradlew.bat assembleDebug

echo.
echo Build complete!
echo APK location: app\build\outputs\apk\debug\app-debug.apk
pause
