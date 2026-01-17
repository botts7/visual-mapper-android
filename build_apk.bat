@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=C:\Users\botts\AppData\Local\Android\Sdk
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%
echo Building Debug APK...
echo JAVA_HOME=%JAVA_HOME%
echo ANDROID_HOME=%ANDROID_HOME%
call "C:\Users\botts\Downloads\Visual Mapper\android-companion\gradlew.bat" -p "C:\Users\botts\Downloads\Visual Mapper\android-companion" assembleDebug
echo Build complete. Exit code: %ERRORLEVEL%
