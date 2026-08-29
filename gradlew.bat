@echo off
REM
REM Gradle wrapper shim (Windows).
REM
REM This repository does not commit a gradle-wrapper.jar, so this script
REM installs the distribution declared in gradle\wrapper\gradle-wrapper.properties.
REM
REM SECURITY: the previous version downloaded a zip and ran it with no integrity
REM check at all. This version reads the URL from the properties file, requires
REM HTTPS and an allowlisted host, verifies the SHA-256 (against
REM distributionSha256Sum when pinned, otherwise against the publisher's
REM .sha256 file), fails closed when no digest can be obtained, and installs by
REM directory swap so an interrupted run can't leave a half-extracted
REM distribution behind that the next run trusts.
setlocal enabledelayedexpansion

set "APP_HOME=%~dp0"
set "PROPS=%APP_HOME%gradle\wrapper\gradle-wrapper.properties"
set "PS=powershell -NoProfile -ExecutionPolicy Bypass -Command"

if not exist "%PROPS%" (
    echo [gradlew] ERROR: missing %PROPS% 1>&2
    exit /b 1
)

REM -- Read distributionUrl / distributionSha256Sum --------------------------
set "DIST_URL="
set "DIST_SHA="
for /f "usebackq tokens=1,* delims==" %%a in ("%PROPS%") do (
    set "_K=%%a"
    set "_V=%%b"
    set "_K=!_K: =!"
    set "_V=!_V: =!"
    REM Gradle escapes the colon in the URL: https\://...
    set "_V=!_V:\:=:!"
    if /i "!_K!"=="distributionUrl"       set "DIST_URL=!_V!"
    if /i "!_K!"=="distributionSha256Sum" set "DIST_SHA=!_V!"
)

if "!DIST_URL!"=="" (
    echo [gradlew] ERROR: distributionUrl is not set in %PROPS% 1>&2
    exit /b 1
)

echo !DIST_URL! | findstr /b /i /c:"https://" >nul
if errorlevel 1 (
    echo [gradlew] ERROR: refusing to download over a non-HTTPS URL: !DIST_URL! 1>&2
    exit /b 1
)

REM -- Host allowlist -------------------------------------------------------
if "%WSM_GRADLE_ALLOWED_HOSTS%"=="" (
    set "ALLOWED_HOSTS=services.gradle.org downloads.gradle.org downloads.gradle-dn.com"
) else (
    set "ALLOWED_HOSTS=%WSM_GRADLE_ALLOWED_HOSTS%"
)
for /f %%h in ('!PS! "([uri]'!DIST_URL!').Host"') do set "DIST_HOST=%%h"
set "HOST_OK="
for %%a in (!ALLOWED_HOSTS!) do (
    if /i "%%a"=="!DIST_HOST!" set "HOST_OK=1"
)
if not defined HOST_OK (
    echo [gradlew] ERROR: distribution host '!DIST_HOST!' is not allowlisted ^(!ALLOWED_HOSTS!^). 1>&2
    echo [gradlew] Set WSM_GRADLE_ALLOWED_HOSTS if this is an intentional mirror. 1>&2
    exit /b 1
)

REM -- Derive names / paths ------------------------------------------------
for %%f in ("!DIST_URL!") do set "ZIP_NAME=%%~nxf"
set "DIST_NAME=!ZIP_NAME:-bin.zip=!"
set "DIST_NAME=!DIST_NAME:-all.zip=!"
set "DIST_NAME=!DIST_NAME:.zip=!"

if "%GRADLE_USER_HOME%"=="" set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "INSTALL_DIR=%GRADLE_USER_HOME%\wrapper\dists\wsm-shim\!DIST_NAME!"
set "GRADLE_BIN=!INSTALL_DIR!\!DIST_NAME!\bin\gradle.bat"

if exist "!GRADLE_BIN!" goto :run

REM -- Download ------------------------------------------------------------
set "TMP_DIR=%TEMP%\gradlew-shim-%RANDOM%%RANDOM%"
mkdir "!TMP_DIR!" || exit /b 1
set "ZIP_PATH=!TMP_DIR!\!ZIP_NAME!"

echo [gradlew] !DIST_NAME! not installed -- downloading !DIST_URL!
!PS! "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '!DIST_URL!' -OutFile '!ZIP_PATH!' -UseBasicParsing"
if errorlevel 1 (
    echo [gradlew] ERROR: download failed. 1>&2
    rmdir /s /q "!TMP_DIR!" 2>nul
    exit /b 1
)

REM -- Verify --------------------------------------------------------------
set "EXPECTED=!DIST_SHA!"
if "!EXPECTED!"=="" (
    echo [gradlew] WARNING: distributionSha256Sum is not pinned in gradle-wrapper.properties. 1>&2
    echo [gradlew] WARNING: falling back to !ZIP_NAME!.sha256 from the same origin, which does 1>&2
    echo [gradlew] WARNING: not protect against an attacker who controls that origin. 1>&2
    !PS! "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '!DIST_URL!.sha256' -OutFile '!TMP_DIR!\expected.sha256' -UseBasicParsing"
    if exist "!TMP_DIR!\expected.sha256" (
        for /f %%s in ('!PS! "(Get-Content -Raw '!TMP_DIR!\expected.sha256').Trim().Substring(0,64).ToLower()"') do set "EXPECTED=%%s"
    )
)

for /f %%s in ('!PS! "(Get-FileHash -Algorithm SHA256 -LiteralPath '!ZIP_PATH!').Hash.ToLower()"') do set "ACTUAL=%%s"

if "!EXPECTED!"=="" (
    echo [gradlew] ERROR: no expected SHA-256 available for !ZIP_NAME! -- refusing to install. 1>&2
    echo [gradlew] After checking it against https://gradle.org/release-checksums/ , add: 1>&2
    echo [gradlew]     distributionSha256Sum=!ACTUAL! 1>&2
    echo [gradlew] to gradle\wrapper\gradle-wrapper.properties. 1>&2
    rmdir /s /q "!TMP_DIR!" 2>nul
    exit /b 1
)
if /i not "!ACTUAL!"=="!EXPECTED!" (
    echo [gradlew] ERROR: SHA-256 mismatch for !ZIP_NAME! 1>&2
    echo [gradlew]   expected: !EXPECTED! 1>&2
    echo [gradlew]   actual:   !ACTUAL! 1>&2
    echo [gradlew] Refusing to install -- this is what a tampered download looks like. 1>&2
    rmdir /s /q "!TMP_DIR!" 2>nul
    exit /b 1
)
echo [gradlew] SHA-256 verified: !ACTUAL!

REM -- Extract and install by directory swap -------------------------------
echo [gradlew] extracting...
!PS! "Expand-Archive -LiteralPath '!ZIP_PATH!' -DestinationPath '!TMP_DIR!\x' -Force"
if errorlevel 1 (
    echo [gradlew] ERROR: extraction failed. 1>&2
    rmdir /s /q "!TMP_DIR!" 2>nul
    exit /b 1
)
if not exist "!TMP_DIR!\x\!DIST_NAME!\bin\gradle.bat" (
    echo [gradlew] ERROR: unexpected archive layout -- !DIST_NAME!\bin\gradle.bat not found. 1>&2
    rmdir /s /q "!TMP_DIR!" 2>nul
    exit /b 1
)

if not exist "!GRADLE_BIN!" (
    for %%p in ("!INSTALL_DIR!") do mkdir "%%~dpp" 2>nul
    set "STAGING=!INSTALL_DIR!.new.%RANDOM%"
    move /y "!TMP_DIR!\x" "!STAGING!" >nul || exit /b 1
    if exist "!INSTALL_DIR!" rmdir /s /q "!INSTALL_DIR!"
    move /y "!STAGING!" "!INSTALL_DIR!" >nul || exit /b 1
)
rmdir /s /q "!TMP_DIR!" 2>nul
echo [gradlew] !DIST_NAME! ready.

:run
call "!GRADLE_BIN!" %*
exit /b !errorlevel!
