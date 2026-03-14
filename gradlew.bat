@echo off
rem Self-bootstrapping Gradle wrapper for Windows — downloads Gradle 8.8 on first run.

set GRADLE_VERSION=9.2.0
set GRADLE_INSTALL_DIR=%USERPROFILE%\.gradle\wrapper\dists\gradle-%GRADLE_VERSION%
set GRADLE_BIN=%GRADLE_INSTALL_DIR%\gradle-%GRADLE_VERSION%\bin\gradle.bat
set GRADLE_ZIP=%GRADLE_INSTALL_DIR%\gradle-%GRADLE_VERSION%-bin.zip
set GRADLE_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip

if not exist "%GRADLE_BIN%" (
    echo [gradlew] Gradle %GRADLE_VERSION% not found -- downloading...
    if not exist "%GRADLE_INSTALL_DIR%" mkdir "%GRADLE_INSTALL_DIR%"

    powershell -Command "& { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('%GRADLE_URL%', '%GRADLE_ZIP%') }"
    if errorlevel 1 (
        echo [gradlew] ERROR: Download failed. Check your internet connection.
        exit /b 1
    )

    echo [gradlew] Extracting...
    powershell -Command "Expand-Archive -Path '%GRADLE_ZIP%' -DestinationPath '%GRADLE_INSTALL_DIR%' -Force"
    del /f "%GRADLE_ZIP%"
    echo [gradlew] Gradle %GRADLE_VERSION% ready.
)

call "%GRADLE_BIN%" %*
