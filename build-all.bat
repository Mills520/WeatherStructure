@echo off
REM Weather & Structure Mod - build all platforms (Windows)
REM Targets Minecraft 26.x only. Produces 3 JARs:
REM   fabric\build\libs\weather-structure-mod-fabric-<ver>.jar
REM   neoforge\build\libs\weather-structure-mod-neoforge-<ver>.jar
REM   paper\build\libs\weather-structure-mod-paper-<ver>.jar
REM
REM Requires Java 25 (MC 26.x enforces this at Loom/ModDevGradle configuration).
setlocal enabledelayedexpansion

call :main
set "_EXITCODE=!errorlevel!"
echo.
echo ====================================================
if "!_EXITCODE!"=="0" (
    echo  Script finished. Check build-all.log for details.
) else (
    echo  Script FAILED with exit code !_EXITCODE!.
    echo  Check build-all.log for details.
)
echo ====================================================
pause
exit /b !_EXITCODE!

:main
set "ROOT=%~dp0"
set "LOGFILE=%ROOT%build-all.log"

echo Build started: %DATE% %TIME% > "%LOGFILE%"
echo Root: %ROOT% >> "%LOGFILE%"

echo.
echo ====================================================
echo  Weather ^& Structure Mod - Full Build
echo  Fabric + NeoForge + Paper ^(MC 26.x, Java 25^)
echo ====================================================
echo.
echo Log file: %LOGFILE%
echo.

REM -- Find Java 25 -----------------------------------------------------
call :find_java 25 JAVA25_HOME
if "!JAVA25_HOME!"=="" (
    echo.
    echo ERROR: Could not find a Java 25 installation.
    echo.
    echo MC 26.x requires Java 25. Install JDK 25 from https://adoptium.net
    echo ^(or any other vendor^) and re-run.
    echo ERROR: Java 25 not found >> "%LOGFILE%"
    exit /b 1
)
echo JAVA25_HOME = !JAVA25_HOME!
echo JAVA25_HOME = !JAVA25_HOME! >> "%LOGFILE%"
"!JAVA25_HOME!\bin\java.exe" -version >> "%LOGFILE%" 2>&1
echo.

REM -- Stop stale daemons so we always start clean under Java 25 ----------
echo Stopping any cached Gradle daemons...
call "%ROOT%gradlew.bat" --stop >nul 2>&1
echo Daemons stopped.
echo.

if not exist "%ROOT%gradlew.bat" (
    echo ERROR: gradlew.bat not found. Run this bat from inside the project folder.
    echo ERROR: gradlew.bat missing >> "%LOGFILE%"
    exit /b 1
)

REM -- Read mod version from gradle.properties --------------------------
set "MOD_VERSION="
for /f "tokens=2 delims==" %%v in ('findstr /b "mod_version" "%ROOT%gradle.properties"') do (
    set "MOD_VERSION=%%v"
)
for /f "tokens=* delims= " %%v in ("%MOD_VERSION%") do set "MOD_VERSION=%%v"
if "%MOD_VERSION%"=="" set "MOD_VERSION=?"

cd /d "%ROOT%"

echo Building Fabric/NeoForge/Paper ^(Java 25, Gradle 9.2^)...
echo Build starting >> "%LOGFILE%"

call gradlew.bat :fabric:build :neoforge:build :paper:build "-Dorg.gradle.java.home=!JAVA25_HOME!" --stacktrace >> "%LOGFILE%" 2>&1
set BUILD_ERR=!errorlevel!
if !BUILD_ERR! neq 0 (
    echo.
    echo ERROR: Build FAILED ^(exit code !BUILD_ERR!^)
    echo Check %LOGFILE% for details.
    echo ERROR: Build failed with code !BUILD_ERR! >> "%LOGFILE%"
    exit /b 1
)

echo [OK] Fabric:   fabric\build\libs\weather-structure-mod-fabric-%MOD_VERSION%.jar
echo [OK] NeoForge: neoforge\build\libs\weather-structure-mod-neoforge-%MOD_VERSION%.jar
echo [OK] Paper:    paper\build\libs\weather-structure-mod-paper-%MOD_VERSION%.jar
echo.
echo ====================================================
echo  All 3 JARs built successfully!
echo ====================================================
echo.
echo Installation:
echo   Fabric / NeoForge JAR  --^>  mods\
echo   Paper JAR              --^>  plugins\
echo.
echo SUCCESS >> "%LOGFILE%"
exit /b 0


REM ====================================================================
REM  find_java <major> <output_var>
REM  Locates a JDK whose `java -version` reports the requested major
REM  version. Sets the named env var to the JDK home, or leaves it
REM  empty if not found.
REM ====================================================================
:find_java
set "_FIND_MAJOR=%~1"
set "_FIND_OUT=%~2"
set "_FIND_RESULT="

echo Searching for Java %_FIND_MAJOR%...

REM 1) Check JAVA_HOME first
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        call :java_major "%JAVA_HOME%\bin\java.exe"
        if "!_JAVA_MAJOR!"=="%_FIND_MAJOR%" (
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
        for /d %%j in ("%%~d\jdk-%_FIND_MAJOR%*" "%%~d\jdk%_FIND_MAJOR%*") do (
            if exist "%%j\bin\java.exe" (
                call :java_major "%%j\bin\java.exe"
                if "!_JAVA_MAJOR!"=="%_FIND_MAJOR%" (
                    set "_FIND_RESULT=%%j"
                    echo   Found via scan: %%j
                    goto :find_java_done
                )
            )
        )
    )
)

:find_java_done
set "%_FIND_OUT%=%_FIND_RESULT%"
exit /b 0


REM ====================================================================
REM  java_major <java.exe path>
REM  Runs `java -version`, extracts the quoted version string from the
REM  first line, leaves the leading numeric component in !_JAVA_MAJOR!.
REM  `usebackq` lets us use double quotes normally inside the command -
REM  the command itself is delimited by backticks, so a path with spaces
REM  just needs the usual one set of double quotes around it.
REM ====================================================================
:java_major
set "_JAVA_MAJOR="
set "_JAVA_RAW="
for /f "usebackq tokens=3" %%v in (`"%~1" -version 2^>^&1`) do (
    if not defined _JAVA_RAW set "_JAVA_RAW=%%v"
)
if not defined _JAVA_RAW exit /b 0
set "_JAVA_RAW=!_JAVA_RAW:"=!"
for /f "tokens=1 delims=.+-" %%m in ("!_JAVA_RAW!") do set "_JAVA_MAJOR=%%m"
exit /b 0
