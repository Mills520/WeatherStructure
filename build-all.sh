#!/bin/sh
# Weather & Structure Mod — build all platforms
# Produces 4 JARs from one command.
#
# Forge uses Gradle 8.8 (ForgeGradle 6 requirement)
# Fabric + NeoForge + Paper use Gradle 9.2 (Loom 1.14 / ModDevGradle 2 requirement)
# These cannot share a single Gradle process — this script bridges them.

set -e

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║    Weather & Structure Mod — Full Build          ║"
echo "║    Fabric + NeoForge + Paper + Forge             ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

# ── Step 1: Fabric, NeoForge, Paper (Gradle 9.2) ─────────────────────────
echo "${YELLOW}[1/2] Building Fabric, NeoForge and Paper (Gradle 9.2)...${NC}"
cd "$ROOT_DIR"
chmod +x gradlew
./gradlew :fabric:build :neoforge:build :paper:build

echo ""
echo "${GREEN}✔ Fabric:   fabric/build/libs/weather-structure-mod-fabric-1.2.0.jar${NC}"
echo "${GREEN}✔ NeoForge: neoforge/build/libs/weather-structure-mod-neoforge-1.2.0.jar${NC}"
echo "${GREEN}✔ Paper:    paper/build/libs/weather-structure-mod-paper-1.2.0.jar${NC}"
echo ""

# ── Step 2: Forge (Gradle 8.8) ────────────────────────────────────────────
echo "${YELLOW}[2/2] Building Forge (Gradle 8.8)...${NC}"
cd "$ROOT_DIR/forge"
chmod +x gradlew
./gradlew build

echo ""
echo "${GREEN}✔ Forge:    forge/build/libs/weather-structure-mod-forge-1.2.0.jar${NC}"
echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║    All 4 JARs built successfully!                ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""
echo "Installation:"
echo "  Fabric JAR   → <instance>/mods/"
echo "  NeoForge JAR → <instance>/mods/"
echo "  Forge JAR    → <instance>/mods/"
echo "  Paper JAR    → <server>/plugins/"
echo ""
