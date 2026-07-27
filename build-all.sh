#!/bin/sh
# Weather & Structure Mod — build all platforms
# Targets Minecraft 26.x only. Produces 3 JARs:
#   fabric/build/libs/weather-structure-mod-fabric-<ver>.jar
#   neoforge/build/libs/weather-structure-mod-neoforge-<ver>.jar
#   paper/build/libs/weather-structure-mod-paper-<ver>.jar
#
# Requires Java 25 (MC 26.x enforces this at Loom/ModDevGradle configuration).

set -eu

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Only emit ANSI colour when stdout is a terminal, and emit it with printf:
# `echo` does not interpret backslash escapes portably (bash's builtin prints
# them literally, dash's expands them), so the old `echo "${RED}...${NC}"` showed
# raw \033[0;31m sequences to anyone whose /bin/sh was bash.
if [ -t 1 ]; then
    RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
else
    RED=''; GREEN=''; YELLOW=''; NC=''
fi
say()  { printf '%b\n' "$*"; }

echo ""
echo "===================================================="
echo "  Weather & Structure Mod — Full Build"
echo "  Fabric + NeoForge + Paper (MC 26.x, Java 25)"
echo "===================================================="
echo ""

# ── java_major <java_bin> → echoes the reported major version ──
java_major() {
    # Pick the line that actually carries the version rather than assuming it is
    # first: a JVM with JAVA_TOOL_OPTIONS or _JAVA_OPTIONS set prints a
    # "Picked up ..." notice ahead of it, and `head -1` then yielded an empty
    # version — so this script reported "Could not find a Java 25 installation"
    # on machines that had one.
    "$1" -version 2>&1 \
        | sed -n 's/.*version "\([^"]*\)".*/\1/p' \
        | head -1 \
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
        "${HOME:-/nonexistent}/.sdkman/candidates/java" \
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
    # ${JAVA_HOME:-} rather than $JAVA_HOME: `set -u` aborts the script on an
    # unset variable, and JAVA_HOME being unset is the normal case here.
    if [ -z "$found" ] && [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
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
    say "${RED}ERROR: Could not find a Java 25 installation.${NC}"
    echo "MC 26.x requires Java 25. Install JDK 25 from https://adoptium.net"
    echo "(or any other vendor) and re-run."
    exit 1
fi
echo "JAVA25_HOME = $JAVA25_HOME"
"$JAVA25_HOME/bin/java" -version 2>&1 | head -1
echo ""

MOD_VERSION=$(sed -n 's/^[[:space:]]*mod_version[[:space:]]*=[[:space:]]*//p' \
    "$ROOT_DIR/gradle.properties" | head -1 | tr -d '\r')
if [ -z "$MOD_VERSION" ]; then MOD_VERSION="?"; fi

cd "$ROOT_DIR"

# Run the wrapper without needing to chmod the checkout: mutating tracked file
# modes as a side effect of building is rude, and fails outright on a read-only
# or root-owned source tree.
if [ -x ./gradlew ]; then
    GRADLEW="./gradlew"
else
    GRADLEW="sh ./gradlew"
fi

# Stop any stale daemon so we always start clean under Java 25.
$GRADLEW --stop >/dev/null 2>&1 || true

say "${YELLOW}Building Fabric/NeoForge/Paper (Java 25, Gradle 9.2)...${NC}"
$GRADLEW :fabric:build :neoforge:build :paper:build \
    "-Dorg.gradle.java.home=$JAVA25_HOME" --stacktrace

echo ""
say "${GREEN}OK Fabric:   fabric/build/libs/weather-structure-mod-fabric-${MOD_VERSION}.jar${NC}"
say "${GREEN}OK NeoForge: neoforge/build/libs/weather-structure-mod-neoforge-${MOD_VERSION}.jar${NC}"
say "${GREEN}OK Paper:    paper/build/libs/weather-structure-mod-paper-${MOD_VERSION}.jar${NC}"
echo ""
echo "===================================================="
echo "  All 3 JARs built successfully!"
echo "===================================================="
echo ""
echo "Installation:"
echo "  Fabric / NeoForge JAR -> <instance>/mods/"
echo "  Paper JAR             -> <server>/plugins/"
echo ""
