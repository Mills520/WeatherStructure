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

# ── Step 1: Fabric, NeoForge, Paper (Gradle 9.2) ─────────────────────────
echo "${YELLOW}[1/2] Building Fabric, NeoForge and Paper (Gradle 9.2)...${NC}"
cd "$ROOT_DIR"
chmod +x gradlew
./gradlew :fabric:build :neoforge:build :paper:build "$JAVA_HOME_FLAG"

echo ""
echo "${GREEN}✔ Fabric:   fabric/build/libs/weather-structure-mod-fabric-1.2.0.jar${NC}"
echo "${GREEN}✔ NeoForge: neoforge/build/libs/weather-structure-mod-neoforge-1.2.0.jar${NC}"
echo "${GREEN}✔ Paper:    paper/build/libs/weather-structure-mod-paper-1.2.0.jar${NC}"
echo ""

# ── Step 2: Forge (Gradle 8.8) ────────────────────────────────────────────
echo "${YELLOW}[2/2] Building Forge (Gradle 8.8)...${NC}"
cd "$ROOT_DIR/forge"
chmod +x gradlew
./gradlew build "$JAVA_HOME_FLAG"

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
