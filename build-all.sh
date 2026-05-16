#!/bin/sh
# Weather & Structure Mod — build all platforms
# Targets Minecraft 26.x only. Produces 3 JARs:
#   fabric/build/libs/weather-structure-mod-fabric-<ver>.jar
#   neoforge/build/libs/weather-structure-mod-neoforge-<ver>.jar
#   paper/build/libs/weather-structure-mod-paper-<ver>.jar
#
# Requires Java 25 (MC 26.x enforces this at Loom/ModDevGradle configuration).

set -e

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

echo ""
echo "===================================================="
echo "  Weather & Structure Mod — Full Build"
echo "  Fabric + NeoForge + Paper (MC 26.x, Java 25)"
echo "===================================================="
echo ""

# ── java_major <java_bin> → echoes the reported major version ──
java_major() {
    "$1" -version 2>&1 | head -1 \
        | sed -E 's/.*version "([^"]+)".*/\1/' \
        | sed -E 's/[.+-].*$//'
}

# ── find_java <major> → echoes the JDK home, empty if not found ──
find_java() {
    target_major="$1"
    found=""
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
                    if [ "$(java_major "$cand/bin/java")" = "$target_major" ]; then
                        found="$cand"
                        break 2
                    fi
                fi
            done
        fi
    done
    if [ -z "$found" ] && [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        if [ "$(java_major "$JAVA_HOME/bin/java")" = "$target_major" ]; then
            found="$JAVA_HOME"
        fi
    fi
    echo "$found"
}

echo "Searching for Java 25..."
JAVA25_HOME="$(find_java 25)"
if [ -z "$JAVA25_HOME" ]; then
    echo ""
    echo "${RED}ERROR: Could not find a Java 25 installation.${NC}"
    echo "MC 26.x requires Java 25. Install JDK 25 from https://adoptium.net"
    echo "(or any other vendor) and re-run."
    exit 1
fi
echo "JAVA25_HOME = $JAVA25_HOME"
"$JAVA25_HOME/bin/java" -version 2>&1 | head -1
echo ""

MOD_VERSION=$(grep -E '^mod_version' "$ROOT_DIR/gradle.properties" | head -1 | sed -E 's/.*=[[:space:]]*//')
if [ -z "$MOD_VERSION" ]; then MOD_VERSION="?"; fi

cd "$ROOT_DIR"
chmod +x gradlew

# Stop any stale daemon so we always start clean under Java 25.
./gradlew --stop >/dev/null 2>&1 || true

echo "${YELLOW}Building Fabric/NeoForge/Paper (Java 25, Gradle 9.2)...${NC}"
./gradlew :fabric:build :neoforge:build :paper:build \
    "-Dorg.gradle.java.home=$JAVA25_HOME"

echo ""
echo "${GREEN}OK Fabric:   fabric/build/libs/weather-structure-mod-fabric-${MOD_VERSION}.jar${NC}"
echo "${GREEN}OK NeoForge: neoforge/build/libs/weather-structure-mod-neoforge-${MOD_VERSION}.jar${NC}"
echo "${GREEN}OK Paper:    paper/build/libs/weather-structure-mod-paper-${MOD_VERSION}.jar${NC}"
echo ""
echo "===================================================="
echo "  All 3 JARs built successfully!"
echo "===================================================="
echo ""
echo "Installation:"
echo "  Fabric / NeoForge JAR -> <instance>/mods/"
echo "  Paper JAR             -> <server>/plugins/"
echo ""
