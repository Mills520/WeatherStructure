#!/bin/sh
# Self-bootstrapping Gradle wrapper — downloads Gradle on first run.
# No gradle-wrapper.jar required.

GRADLE_VERSION="9.2.0"
GRADLE_INSTALL_DIR="$HOME/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}"
GRADLE_BIN="${GRADLE_INSTALL_DIR}/gradle-${GRADLE_VERSION}/bin/gradle"
GRADLE_ZIP="${GRADLE_INSTALL_DIR}/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

if [ ! -f "$GRADLE_BIN" ]; then
    echo "[gradlew] Gradle ${GRADLE_VERSION} not found — downloading..."
    mkdir -p "$GRADLE_INSTALL_DIR"

    if command -v curl >/dev/null 2>&1; then
        curl -fL --progress-bar -o "$GRADLE_ZIP" "$GRADLE_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --show-progress -O "$GRADLE_ZIP" "$GRADLE_URL"
    else
        echo "[gradlew] ERROR: Neither curl nor wget found." >&2
        exit 1
    fi

    echo "[gradlew] Extracting..."
    unzip -q "$GRADLE_ZIP" -d "$GRADLE_INSTALL_DIR"
    rm -f "$GRADLE_ZIP"
    echo "[gradlew] Gradle ${GRADLE_VERSION} ready."
fi

exec "$GRADLE_BIN" "$@"
