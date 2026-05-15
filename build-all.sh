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

# ── Find Java 21 ─────────────────────────────────────────────────────────
echo "Searching for Java 21..."
FOUND_JAVA=""

# 1) Check if JAVA_HOME already points to 21.x
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    if "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21\.'; then
        FOUND_JAVA="$JAVA_HOME"
        echo "Found via JAVA_HOME: $FOUND_JAVA"
    fi
fi

# 2) Scan common Linux/macOS JDK locations
if [ -z "$FOUND_JAVA" ]; then
    for base in \
        /usr/lib/jvm \
        /usr/java \
        /opt/java \
        /opt/jdk \
        "$HOME/.sdkman/candidates/java" \
        /Library/Java/JavaVirtualMachines; do
        if [ -d "$base" ]; then
            for d in "$base"/jdk-21* "$base"/java-21* "$base"/temurin-21* "$base"/21.*; do
                if [ -d "$d" ]; then
                    # macOS JVMs nest inside Contents/Home
                    if [ -x "$d/Contents/Home/bin/java" ]; then
                        FOUND_JAVA="$d/Contents/Home"
                    elif [ -x "$d/bin/java" ]; then
                        FOUND_JAVA="$d"
                    fi
                    if [ -n "$FOUND_JAVA" ]; then
                        echo "Found via scan: $FOUND_JAVA"
                        break 2
                    fi
                fi
            done
        fi
    done
fi

# 3) Fall back to whatever 'java' is on PATH
if [ -z "$FOUND_JAVA" ]; then
    if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q '"21\.'; then
        FOUND_JAVA="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
        echo "Found via PATH: $FOUND_JAVA"
    fi
fi

if [ -z "$FOUND_JAVA" ]; then
    echo ""
    echo "${RED}ERROR: Could not find a Java 21 installation.${NC}"
    echo ""
    echo "Please install Java 21 from https://adoptium.net"
    echo "and set JAVA_HOME to the JDK folder."
    exit 1
fi

export JAVA_HOME="$FOUND_JAVA"
export PATH="$JAVA_HOME/bin:$PATH"
echo "JAVA_HOME = $JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
echo "Java 21 OK."
echo ""

JAVA_HOME_FLAG="-Dorg.gradle.java.home=$JAVA_HOME"

# Read the canonical mod version from gradle.properties so this script never
# drifts from the real build outputs.
MOD_VERSION=$(grep -E '^mod_version' "$ROOT_DIR/gradle.properties" | head -1 | sed -E 's/.*=[[:space:]]*//')
if [ -z "$MOD_VERSION" ]; then MOD_VERSION="?"; fi

# ── Step 1: Fabric, NeoForge, Paper — 1.21.x AND 26.1.x lines (Gradle 9.2) ──
echo "${YELLOW}[1/2] Building Fabric, NeoForge and Paper for both MC lines (Gradle 9.2)...${NC}"
cd "$ROOT_DIR"
chmod +x gradlew
./gradlew \
    :fabric:build :neoforge:build :paper:build \
    :fabric-26x:build :neoforge-26x:build :paper-26x:build \
    "$JAVA_HOME_FLAG"

echo ""
echo "${GREEN}✔ Fabric 1.21.x:    fabric/build/libs/weather-structure-mod-fabric-${MOD_VERSION}.jar${NC}"
echo "${GREEN}✔ NeoForge 1.21.x:  neoforge/build/libs/weather-structure-mod-neoforge-${MOD_VERSION}.jar${NC}"
echo "${GREEN}✔ Paper 1.21.x:     paper/build/libs/weather-structure-mod-paper-${MOD_VERSION}.jar${NC}"
echo "${GREEN}✔ Fabric 26.1.x:    fabric-26x/build/libs/weather-structure-mod-fabric-26x-${MOD_VERSION}.jar${NC}"
echo "${GREEN}✔ NeoForge 26.1.x:  neoforge-26x/build/libs/weather-structure-mod-neoforge-26x-${MOD_VERSION}.jar${NC}"
echo "${GREEN}✔ Paper 26.1.x:     paper-26x/build/libs/weather-structure-mod-paper-26x-${MOD_VERSION}.jar${NC}"
echo ""

# ── Step 2: Forge (Gradle 8.8) ────────────────────────────────────────────
echo "${YELLOW}[2/2] Building Forge (Gradle 8.8)...${NC}"
cd "$ROOT_DIR/forge"
chmod +x gradlew
./gradlew build "$JAVA_HOME_FLAG"

echo ""
echo "${GREEN}✔ Forge 1.21.x:     forge/build/libs/weather-structure-mod-forge-${MOD_VERSION}.jar${NC}"
echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║    All 7 JARs built successfully!                ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""
echo "Installation:"
echo "  Fabric / NeoForge / Forge JAR   → <instance>/mods/"
echo "  Paper JAR                       → <server>/plugins/"
echo ""
echo "Pick the JAR matching your Minecraft version (1.21.x or 26.1.x)."
echo ""
