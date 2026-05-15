@echo off
REM Weather & Structure Mod — build all platforms (Windows)
REM Produces 7 JARs (4 × 1.21.x + 3 × 26.1.x).
REM
REM Java requirements:
REM   MC 1.21.x line + Forge → Java 21
REM   MC 26.1.x line         → Java 25 (Loom/MC enforces this at configuration)
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
echo  1.21.x line (Java 21) + 26.1.x line (Java 25)
echo ====================================================
echo.
echo Log file: %LOGFILE%
echo.

REM ── Find Java 21 ─────────────────────────────────────────────────────
call :find_java 21 JAVA21_HOME
if "!JAVA21_HOME!"=="" (
    echo.
    echo ERROR: Could not find a Java 21 installation.
    echo.
    echo Install Java 21 from https://adoptium.net
    echo ^(needed for the 1.21.x line + Forge^)
    echo ERROR: Java 21 not found >> "%LOGFILE%"
    exit /b 1
)
echo JAVA21_HOME = !JAVA21_HOME!
echo JAVA21_HOME = !JAVA21_HOME! >> "%LOGFILE%"
"!JAVA21_HOME!\bin\java.exe" -version >> "%LOGFILE%" 2>&1

REM ── Find Java 25 ─────────────────────────────────────────────────────
call :find_java 25 JAVA25_HOME
if "!JAVA25_HOME!"=="" (
    echo.
    echo ERROR: Could not find a Java 25 installation.
    echo.
    echo MC 26.1.x requires Java 25. Install JDK 25 from https://adoptium.net
    echo ^(or any other vendor^) and re-run.
    echo ERROR: Java 25 not found >> "%LOGFILE%"
    exit /b 1
)
echo JAVA25_HOME = !JAVA25_HOME!
echo JAVA25_HOME = !JAVA25_HOME! >> "%LOGFILE%"
"!JAVA25_HOME!\bin\java.exe" -version >> "%LOGFILE%" 2>&1
echo.

REM ── Stop any cached Gradle daemons (prevents stale JDK caching) ─────────
echo Stopping any cached Gradle daemons...
call "%ROOT%gradlew.bat" --stop >nul 2>&1
call "%ROOT%forge\gradlew.bat" --stop >nul 2>&1
echo Daemons stopped.
echo.

if not exist "%ROOT%gradlew.bat" (
    echo ERROR: gradlew.bat not found. Run this bat from inside the project folder.
    echo ERROR: gradlew.bat missing >> "%LOGFILE%"
    exit /b 1
)

REM ── Read mod version from gradle.properties ──────────────────────────
set "MOD_VERSION="
for /f "tokens=2 delims==" %%v in ('findstr /b "mod_version" "%ROOT%gradle.properties"') do (
    set "MOD_VERSION=%%v"
)
for /f "tokens=* delims= " %%v in ("%MOD_VERSION%") do set "MOD_VERSION=%%v"
if "%MOD_VERSION%"=="" set "MOD_VERSION=?"

cd /d "%ROOT%"

REM ── Step 1: 1.21.x line (Java 21) ────────────────────────────────────
echo [1/3] Building Fabric/NeoForge/Paper for MC 1.21.x ^(Java 21, Gradle 9.2^)...
echo [1/3] 1.21.x build starting >> "%LOGFILE%"

call gradlew.bat :fabric:build :neoforge:build :paper:build "-Dorg.gradle.java.home=!JAVA21_HOME!" >> "%LOGFILE%" 2>&1
set STEP1_ERR=!errorlevel!
if !STEP1_ERR! neq 0 (
    echo.
    echo ERROR: 1.21.x build FAILED ^(exit code !STEP1_ERR!^)
    echo Check %LOGFILE% for details.
    echo ERROR: Step 1 failed with code !STEP1_ERR! >> "%LOGFILE%"
    exit /b 1
)

echo [OK] Fabric 1.21.x:    fabric\build\libs\weather-structure-mod-fabric-%MOD_VERSION%.jar
echo [OK] NeoForge 1.21.x:  neoforge\build\libs\weather-structure-mod-neoforge-%MOD_VERSION%.jar
echo [OK] Paper 1.21.x:     paper\build\libs\weather-structure-mod-paper-%MOD_VERSION%.jar
echo.

REM ── Step 2: 26.1.x line (Java 25) ────────────────────────────────────
echo [2/3] Building Fabric/NeoForge/Paper for MC 26.1.x ^(Java 25, Gradle 9.2^)...
echo [2/3] 26.1.x build starting >> "%LOGFILE%"

REM Stop the daemon from step 1 — it was started under Java 21 and will refuse
REM to run the Java 25 build.
call "%ROOT%gradlew.bat" --stop >nul 2>&1

call gradlew.bat :fabric-26x:build :neoforge-26x:build :paper-26x:build "-Dorg.gradle.java.home=!JAVA25_HOME!" >> "%LOGFILE%" 2>&1
set STEP2_ERR=!errorlevel!
if !STEP2_ERR! neq 0 (
    echo.
    echo ERROR: 26.1.x build FAILED ^(exit code !STEP2_ERR!^)
    echo Check %LOGFILE% for details.
    echo ERROR: Step 2 failed with code !STEP2_ERR! >> "%LOGFILE%"
    exit /b 1
)

echo [OK] Fabric 26.1.x:    fabric-26x\build\libs\weather-structure-mod-fabric-26x-%MOD_VERSION%.jar
echo [OK] NeoForge 26.1.x:  neoforge-26x\build\libs\weather-structure-mod-neoforge-26x-%MOD_VERSION%.jar
echo [OK] Paper 26.1.x:     paper-26x\build\libs\weather-structure-mod-paper-26x-%MOD_VERSION%.jar
echo.

REM ── Step 3: Forge (Java 21, Gradle 8.8) ──────────────────────────────
echo [3/3] Building Forge for MC 1.21.x ^(Java 21, Gradle 8.8^)...
echo [3/3] Forge build starting >> "%LOGFILE%"

if not exist "%ROOT%forge\gradlew.bat" (
    echo ERROR: forge\gradlew.bat not found.
    echo ERROR: forge\gradlew.bat missing >> "%LOGFILE%"
    exit /b 1
)

cd /d "%ROOT%forge"
call gradlew.bat build "-Dorg.gradle.java.home=!JAVA21_HOME!" >> "%LOGFILE%" 2>&1
set STEP3_ERR=!errorlevel!
if !STEP3_ERR! neq 0 (
    echo.
    echo ERROR: Forge build FAILED ^(exit code !STEP3_ERR!^)
    echo Check %LOGFILE% for details.
    echo ERROR: Step 3 failed with code !STEP3_ERR! >> "%LOGFILE%"
    exit /b 1
)

echo [OK] Forge 1.21.x:     forge\build\libs\weather-structure-mod-forge-%MOD_VERSION%.jar
echo.
echo ====================================================
echo  All 7 JARs built successfully!
echo ====================================================
echo.
echo Installation:
echo   Fabric / NeoForge / Forge JAR  --^>  mods\
echo   Paper JAR                       --^>  plugins\
echo.
echo Pick the JAR matching your Minecraft version ^(1.21.x or 26.1.x^).
echo.
echo SUCCESS >> "%LOGFILE%"
exit /b 0


REM ════════════════════════════════════════════════════════════════════
REM  find_java <major> <output_var>
REM  Searches for a JDK whose 'java -version' output starts with the
REM  requested major version. Sets the named env var to the JDK home, or
REM  leaves it empty if not found.
REM ════════════════════════════════════════════════════════════════════
:find_java
set "_FIND_MAJOR=%~1"
set "_FIND_OUT=%~2"
set "_FIND_RESULT="

echo Searching for Java %_FIND_MAJOR%...

REM 1) Check JAVA_HOME for an exact match
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        "%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /r /c:"\"%_FIND_MAJOR%\." >nul
        if !errorlevel! equ 0 (
            set "_FIND_RESULT=%JAVA_HOME%"
            echo   Found via JAVA_HOME: %JAVA_HOME%
            goto :find_java_done
        )
    )
)

REM 2) Scan well-known install dirs
for %%d in (
    "C:\Program Files\Java"
    "C:\Program Files\Eclipse Adoptium"
    "C:\Program Files\Microsoft"
    "C:\Program Files\Amazon Corretto"
    "C:\Program Files\BellSoft"
    "C:\Program Files\Zulu"
) do (
    if exist "%%~d" (
        for /d %%j in ("%%~d\jdk-%_FIND_MAJOR%.*") do (
            if exist "%%j\bin\java.exe" (
                set "_FIND_RESULT=%%j"
                echo   Found via scan: %%j
                goto :find_java_done
            )
        )
        for /d %%j in ("%%~d\jdk%_FIND_MAJOR%.*") do (
            if exist "%%j\bin\java.exe" (
                set "_FIND_RESULT=%%j"
                echo   Found via scan: %%j
                goto :find_java_done
            )
        )
    )
)

:find_java_done
set "%_FIND_OUT%=%_FIND_RESULT%"
exit /b 0
