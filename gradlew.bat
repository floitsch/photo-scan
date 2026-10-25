@echo off
set APP_DIR=%~dp0
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" -classpath "%APP_DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
) else (
  java.exe -classpath "%APP_DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
)
