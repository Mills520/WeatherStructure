@echo off
REM Weather & Structure Mod — build all platforms (Windows)
setlocal enabledelayedexpansion

set ROOT=%~dp0

echo.
echo ====================================================
echo  Weather ^& Structure Mod — Full Build
echo  Fabric + NeoForge + Paper + Forge
echo ====================================================
echo.

REM Step 1: Fabric, NeoForge, Paper (Gradle 9.2)
echo [1/2] Building Fabric, NeoForge and Paper (Gradle 9.2)...
cd /d "%ROOT%"
call gradlew.bat :fabric:build :neoforge:build :paper:build
if errorlevel 1 ( echo ERROR: Fabric/NeoForge/Paper build failed! & exit /b 1 )

echo.
echo [OK] Fabric:   fabric\build\libs\weather-structure-mod-fabric-1.0.0.jar
echo [OK] NeoForge: neoforge\build\libs\weather-structure-mod-neoforge-1.0.0.jar
echo [OK] Paper:    paper\build\libs\weather-structure-mod-paper-1.0.0.jar
echo.

REM Step 2: Forge (Gradle 8.8)
echo [2/2] Building Forge (Gradle 8.8)...
cd /d "%ROOT%forge"
call gradlew.bat build
if errorlevel 1 ( echo ERROR: Forge build failed! & exit /b 1 )

echo.
echo [OK] Forge: forge\build\libs\weather-structure-mod-forge-1.0.0.jar
echo.
echo ====================================================
echo  All 4 JARs built successfully!
echo ====================================================
echo.
echo Installation:
echo   Fabric / NeoForge / Forge JAR  -- mods\
echo   Paper JAR                       -- plugins\
echo.
