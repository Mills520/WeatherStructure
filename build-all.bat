@echo off
REM Weather & Structure Mod — build all platforms (Windows)
setlocal enabledelayedexpansion

call :main
echo.
echo ════════════════════════════════════════════════════
echo  Script finished. Check build-all.log for details.
echo ════════════════════════════════════════════════════
pause
exit /b

:main
set "ROOT=%~dp0"
set "LOGFILE=%ROOT%build-all.log"

echo Build started: %DATE% %TIME% > "%LOGFILE%"
echo Root: %ROOT% >> "%LOGFILE%"

echo.
echo ====================================================
echo  Weather ^& Structure Mod — Full Build
echo  Fabric + NeoForge + Paper + Forge
echo ====================================================
echo.
echo Log file: %LOGFILE%
echo.

REM ── Find Java 21 ─────────────────────────────────────────────────────
echo Searching for Java 21...
set "FOUND_JAVA="

REM 1) Check if JAVA_HOME already points to 21.x
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        "%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /i "21\." >nul
        if !errorlevel! equ 0 (
            set "FOUND_JAVA=%JAVA_HOME%"
            echo Found via JAVA_HOME: %JAVA_HOME%
            goto :java_found
        ) else (
            echo JAVA_HOME exists but is not Java 21, scanning... >> "%LOGFILE%"
        )
    )
)

REM 2) Scan Program Files for jdk-21.x.x folders
for %%d in (
    "C:\Program Files\Java"
    "C:\Program Files\Eclipse Adoptium"
    "C:\Program Files\Microsoft"
    "C:\Program Files\Amazon Corretto"
    "C:\Program Files\BellSoft"
) do (
    if exist "%%~d" (
        for /d %%j in ("%%~d\jdk-21.*") do (
            if exist "%%j\bin\java.exe" (
                set "FOUND_JAVA=%%j"
                echo Found via scan: %%j
                goto :java_found
            )
        )
        for /d %%j in ("%%~d\jdk21.*") do (
            if exist "%%j\bin\java.exe" (
                set "FOUND_JAVA=%%j"
                echo Found via scan: %%j
                goto :java_found
            )
        )
    )
)

REM 3) Check PrismLauncher bundled JRE
for /d %%j in ("%APPDATA%\PrismLauncher\java\java-runtime-delta*") do (
    if exist "%%j\bin\java.exe" (
        set "FOUND_JAVA=%%j"
        echo Found via PrismLauncher: %%j
        goto :java_found
    )
)

echo.
echo ERROR: Could not find a Java 21 installation.
echo.
echo Please install Java 21 from https://adoptium.net
echo and set JAVA_HOME to the JDK folder, e.g.:
echo   C:\Program Files\Eclipse Adoptium\jdk-21.0.10+8
echo.
echo ERROR: Java 21 not found >> "%LOGFILE%"
exit /b 1

:java_found
REM Force JAVA_HOME and PATH to our found Java 21
set "JAVA_HOME=%FOUND_JAVA%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo JAVA_HOME = %JAVA_HOME% >> "%LOGFILE%"
echo JAVA_HOME = %JAVA_HOME%

REM Verify it really is Java 21
"%JAVA_HOME%\bin\java.exe" -version >> "%LOGFILE%" 2>&1
echo Java 21 OK.
echo.

REM ── Stop any cached Gradle daemons (prevents stale JDK caching) ─────────
echo Stopping any cached Gradle daemons...
call "%ROOT%gradlew.bat" --stop >nul 2>&1
call "%ROOT%forge\gradlew.bat" --stop >nul 2>&1
echo Daemons stopped.
echo.

REM ── Check gradlew.bat exists ─────────────────────────────────────────
if not exist "%ROOT%gradlew.bat" (
    echo ERROR: gradlew.bat not found. Run this bat from inside the wsm-all folder.
    echo ERROR: gradlew.bat missing >> "%LOGFILE%"
    exit /b 1
)

REM ── Step 1: Fabric, NeoForge, Paper ──────────────────────────────────
echo [1/2] Building Fabric, NeoForge and Paper ^(Gradle 9.2^)...
echo [1/2] Fabric+NeoForge+Paper build starting >> "%LOGFILE%"

cd /d "%ROOT%"
REM Pass -Dorg.gradle.java.home to force Gradle to use OUR Java 21, not any toolchain
call gradlew.bat :fabric:build :neoforge:build :paper:build "-Dorg.gradle.java.home=%JAVA_HOME%" >> "%LOGFILE%" 2>&1
set STEP1_ERR=%errorlevel%

if %STEP1_ERR% neq 0 (
    echo.
    echo ERROR: Fabric/NeoForge/Paper build FAILED ^(exit code %STEP1_ERR%^)
    echo Check %LOGFILE% for details.
    echo ERROR: Step 1 failed with code %STEP1_ERR% >> "%LOGFILE%"
    exit /b 1
)

echo [OK] Fabric:   fabric\build\libs\weather-structure-mod-fabric-1.1.0.jar
echo [OK] NeoForge: neoforge\build\libs\weather-structure-mod-neoforge-1.1.0.jar
echo [OK] Paper:    paper\build\libs\weather-structure-mod-paper-1.1.0.jar
echo.

REM ── Step 2: Forge ────────────────────────────────────────────────────
echo [2/2] Building Forge ^(Gradle 8.8^)...
echo [2/2] Forge build starting >> "%LOGFILE%"

if not exist "%ROOT%forge\gradlew.bat" (
    echo ERROR: forge\gradlew.bat not found.
    echo ERROR: forge\gradlew.bat missing >> "%LOGFILE%"
    exit /b 1
)

cd /d "%ROOT%forge"
call gradlew.bat build "-Dorg.gradle.java.home=%JAVA_HOME%" >> "%LOGFILE%" 2>&1
set STEP2_ERR=%errorlevel%

if %STEP2_ERR% neq 0 (
    echo.
    echo ERROR: Forge build FAILED ^(exit code %STEP2_ERR%^)
    echo Check %LOGFILE% for details.
    echo ERROR: Step 2 failed with code %STEP2_ERR% >> "%LOGFILE%"
    exit /b 1
)

echo [OK] Forge: forge\build\libs\weather-structure-mod-forge-1.1.0.jar
echo.
echo ====================================================
echo  All 4 JARs built successfully!
echo ====================================================
echo.
echo Installation:
echo   Fabric / NeoForge / Forge JAR  --^>  mods\
echo   Paper JAR                       --^>  plugins\
echo.
echo SUCCESS >> "%LOGFILE%"
exit /b 0
