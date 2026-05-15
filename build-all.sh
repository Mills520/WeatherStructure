#!/bin/sh
# Weather & Structure Mod — build all platforms
# Produces 7 JARs from one command (4 × 1.21.x + 3 × 26.1.x).
#
# Java requirements:
#   MC 1.21.x line + Forge → Java 21
#   MC 26.1.x line         → Java 25 (Loom/MC enforces this at configuration)
#
# Forge uses Gradle 8.8 (ForgeGradle 6 requirement)
# Fabric + NeoForge + Paper use Gradle 9.2 (Loom 1.14+ / ModDevGradle 2)

set -e

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║    Weather & Structure Mod — Full Build          ║"
echo "║    1.21.x line (Java 21) + 26.1.x line (Java 25) ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

# ── find_java <major_version> → echoes the JDK home, empty if not found ──
find_java() {
    target_major="$1"
    found=""

    # 1) Check JAVA_HOME first (only if a positional arg isn't passed; we keep
    #    this for the script's auto-detect behaviour). For per-version search
    #    we skip JAVA_HOME because it's ambiguous when two JDKs are needed.

    # 2) Scan common Linux/macOS JDK locations
    for base in \
        /usr/lib/jvm \
        /usr/java \
        /opt/java \
        /opt/jdk \
        "$HOME/.sdkman/candidates/java" \
        /Library/Java/JavaVirtualMachines; do
        if [ -d "$base" ]; then
            for d in "$base"/jdk-"$target_major"* "$base"/java-"$target_major"* \
                     "$base"/temurin-"$target_major"* "$base"/"$target_major".*; do
                if [ -d "$d" ]; then
                    if [ -x "$d/Contents/Home/bin/java" ]; then
                        cand="$d/Contents/Home"
                    elif [ -x "$d/bin/java" ]; then
                        cand="$d"
                    else
                        continue
                    fi
                    if "$cand/bin/java" -version 2>&1 | grep -q "\"$target_major\\."; then
                        found="$cand"
                        break 2
                    fi
                fi
            done
        fi
    done

    # 3) If JAVA_HOME points to the requested version, accept it
    if [ -z "$found" ] && [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        if "$JAVA_HOME/bin/java" -version 2>&1 | grep -q "\"$target_major\\."; then
            found="$JAVA_HOME"
        fi
    fi

    echo "$found"
}

# ── Find Java 21 (for 1.21.x line + Forge) ──────────────────────────────
echo "Searching for Java 21..."
JAVA21_HOME="$(find_java 21)"
if [ -z "$JAVA21_HOME" ]; then
    echo ""
    echo "${RED}ERROR: Could not find a Java 21 installation.${NC}"
    echo "Install Java 21 from https://adoptium.net (needed for the 1.21.x line + Forge)."
    exit 1
fi
echo "JAVA21_HOME = $JAVA21_HOME"
"$JAVA21_HOME/bin/java" -version 2>&1 | head -1

# ── Find Java 25 (for 26.1.x line) ──────────────────────────────────────
echo ""
echo "Searching for Java 25..."
JAVA25_HOME="$(find_java 25)"
if [ -z "$JAVA25_HOME" ]; then
    echo ""
    echo "${RED}ERROR: Could not find a Java 25 installation.${NC}"
    echo "MC 26.1.x requires Java 25. Install JDK 25 from https://adoptium.net"
    echo "(or any other vendor) and re-run."
    exit 1
fi
echo "JAVA25_HOME = $JAVA25_HOME"
"$JAVA25_HOME/bin/java" -version 2>&1 | head -1
echo ""

# Read the canonical mod version from gradle.properties so this script never
# drifts from the real build outputs.
MOD_VERSION=$(grep -E '^mod_version' "$ROOT_DIR/gradle.properties" | head -1 | sed -E 's/.*=[[:space:]]*//')
if [ -z "$MOD_VERSION" ]; then MOD_VERSION="?"; fi

cd "$ROOT_DIR"
chmod +x gradlew

# Stop any stale daemons so they're not running under the wrong JDK.
./gradlew --stop >/dev/null 2>&1 || true

# ── Step 1: 1.21.x line (Java 21) ───────────────────────────────────────
echo "${YELLOW}[1/3] Building Fabric/NeoForge/Paper for MC 1.21.x (Java 21, Gradle 9.2)...${NC}"
./gradlew :fabric:build :neoforge:build :paper:build "-Dorg.gradle.java.home=$JAVA21_HOME"

echo ""
echo "${GREEN}✔ Fabric 1.21.x:    fabric/build/libs/weather-structure-mod-fabric-${MOD_VERSION}.jar${NC}"
echo "${GREEN}✔ NeoForge 1.21.x:  neoforge/build/libs/weather-structure-mod-neoforge-${MOD_VERSION}.jar${NC}"
echo "${GREEN}✔ Paper 1.21.x:     paper/build/libs/weather-structure-mod-paper-${MOD_VERSION}.jar${NC}"
echo ""

# Stop the Java-21 daemon so step 2 starts a fresh Java-25 daemon.
./gradlew --stop >/dev/null 2>&1 || true

# ── Step 2: 26.1.x line (Java 25) ───────────────────────────────────────
echo "${YELLOW}[2/3] Building Fabric/NeoForge/Paper for MC 26.1.x (Java 25, Gradle 9.2)...${NC}"
./gradlew :fabric-26x:build :neoforge-26x:build :paper-26x:build "-Dorg.gradle.java.home=$JAVA25_HOME"

echo ""
echo "${GREEN}✔ Fabric 26.1.x:    fabric-26x/build/libs/weather-structure-mod-fabric-26x-${MOD_VERSION}.jar${NC}"
echo "${GREEN}✔ NeoForge 26.1.x:  neoforge-26x/build/libs/weather-structure-mod-neoforge-26x-${MOD_VERSION}.jar${NC}"
echo "${GREEN}✔ Paper 26.1.x:     paper-26x/build/libs/weather-structure-mod-paper-26x-${MOD_VERSION}.jar${NC}"
echo ""

# ── Step 3: Forge (Java 21, Gradle 8.8) ─────────────────────────────────
echo "${YELLOW}[3/3] Building Forge for MC 1.21.x (Java 21, Gradle 8.8)...${NC}"
cd "$ROOT_DIR/forge"
chmod +x gradlew
./gradlew build "-Dorg.gradle.java.home=$JAVA21_HOME"

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
