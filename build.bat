@echo off
rem Builds the debug APK with Android Studio's bundled JDK. Output: build.log
cd /d "%~dp0"
if not defined JAVA_HOME set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not exist local.properties echo sdk.dir=%ANDROID_HOME:\=/%> local.properties
call gradlew.bat assembleDebug testDebugUnitTest --no-daemon --console=plain > build.log 2>&1
echo EXIT=%ERRORLEVEL%>> build.log
