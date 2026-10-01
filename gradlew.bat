@echo off
setlocal
set APP_HOME=%~dp0
if exist "%JAVA_HOME%\bin\java.exe" (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" (
  set "JAVA_EXE=%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=gradlew" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
