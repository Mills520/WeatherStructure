#!/bin/sh
#
# Gradle wrapper shim.
#
# This repository does not commit a gradle-wrapper.jar, so this script installs
# the distribution declared in gradle/wrapper/gradle-wrapper.properties itself.
#
# SECURITY: the previous version of this script downloaded a zip over the
# network, unzipped it, and exec'd the result with no integrity check of any
# kind — anything that could answer for that download (a MITM, a poisoned proxy
# cache, an edited URL) got arbitrary code execution as the building user. This
# version:
#
#   * reads the URL from gradle-wrapper.properties, so the shim can't drift out
#     of sync with the declared distribution,
#   * refuses anything that is not HTTPS, and refuses hosts outside a small
#     publisher allowlist,
#   * verifies SHA-256 against `distributionSha256Sum` when it is pinned in
#     gradle-wrapper.properties — this is the strong control, please pin it,
#   * otherwise verifies against the publisher's `<url>.sha256` and prints the
#     digest so you can pin it, and fails closed if no digest can be obtained,
#   * downloads into a private temp directory and installs by directory swap, so
#     an interrupted run cannot leave a half-extracted distribution behind that
#     the next run treats as good.

set -eu

APP_HOME=$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd)
PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

# Hosts Gradle publishes distributions from. Set WSM_GRADLE_ALLOWED_HOSTS to a
# space-separated list to override (e.g. for an internal mirror).
ALLOWED_HOSTS="${WSM_GRADLE_ALLOWED_HOSTS:-services.gradle.org downloads.gradle.org downloads.gradle-dn.com}"

die()  { printf '[gradlew] ERROR: %s\n' "$*" >&2; exit 1; }
warn() { printf '[gradlew] WARNING: %s\n' "$*" >&2; }
info() { printf '[gradlew] %s\n' "$*"; }

[ -f "$PROPS" ] || die "missing $PROPS"

# Reads one key from the properties file. Values may contain the escaped colon
# Gradle writes (distributionUrl=https\://...), so unescape it.
prop() {
    sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$PROPS" \
        | head -1 | tr -d '\r' | sed 's/\\:/:/g'
}

DIST_URL=$(prop distributionUrl)
DIST_SHA=$(prop distributionSha256Sum)
[ -n "$DIST_URL" ] || die "distributionUrl is not set in $PROPS"

case "$DIST_URL" in
    https://*) ;;
    *) die "refusing to download over a non-HTTPS URL: $DIST_URL" ;;
esac

DIST_HOST=$(printf '%s' "$DIST_URL" \
    | sed -e 's#^https://##' -e 's#/.*##' -e 's#.*@##' -e 's#:[0-9]*$##')
host_allowed=no
for allowed in $ALLOWED_HOSTS; do
    if [ "$DIST_HOST" = "$allowed" ]; then host_allowed=yes; break; fi
done
[ "$host_allowed" = yes ] || die "distribution host '$DIST_HOST' is not in the allowlist ($ALLOWED_HOSTS).
Set WSM_GRADLE_ALLOWED_HOSTS if this is an intentional mirror."

ZIP_NAME=${DIST_URL##*/}
case "$ZIP_NAME" in
    *-bin.zip) DIST_NAME=${ZIP_NAME%-bin.zip} ;;
    *-all.zip) DIST_NAME=${ZIP_NAME%-all.zip} ;;
    *.zip)     DIST_NAME=${ZIP_NAME%.zip} ;;
    *)         die "distributionUrl does not point at a .zip: $DIST_URL" ;;
esac

: "${GRADLE_USER_HOME:=$HOME/.gradle}"
INSTALL_DIR="$GRADLE_USER_HOME/wrapper/dists/wsm-shim/$DIST_NAME"
GRADLE_BIN="$INSTALL_DIR/$DIST_NAME/bin/gradle"

sha256_of() {
    if   command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum    >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
    elif command -v openssl   >/dev/null 2>&1; then openssl dgst -sha256 "$1" | sed 's/.*= *//'
    else return 1
    fi
}

# Downloads $1 to $2. --proto '=https' / --https-only stop a redirect from
# downgrading the transport mid-transfer.
fetch() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --proto '=https' --tlsv1.2 --retry 3 --retry-delay 2 -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --https-only --tries=3 -O "$2" "$1"
    else
        die "neither curl nor wget is available to download Gradle"
    fi
}

if [ ! -x "$GRADLE_BIN" ]; then
    command -v unzip >/dev/null 2>&1 || die "unzip is required to install Gradle"
    sha256_of /dev/null >/dev/null 2>&1 \
        || die "no SHA-256 tool found (need sha256sum, shasum or openssl); refusing to install an unverified Gradle"

    TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/gradlew-shim.XXXXXX")
    trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM

    info "$DIST_NAME not installed — downloading $DIST_URL"
    fetch "$DIST_URL" "$TMP_DIR/$ZIP_NAME"

    expected=$DIST_SHA
    if [ -z "$expected" ]; then
        warn "distributionSha256Sum is not pinned in gradle-wrapper.properties."
        warn "Falling back to ${ZIP_NAME}.sha256 from the same origin, which does"
        warn "not protect against an attacker who controls that origin. Pin the"
        warn "digest printed below to get a real guarantee."
        if fetch "${DIST_URL}.sha256" "$TMP_DIR/expected.sha256"; then
            expected=$(tr -d ' \t\r\n' < "$TMP_DIR/expected.sha256" | cut -c1-64)
        else
            warn "could not download ${ZIP_NAME}.sha256"
        fi
    fi

    actual=$(sha256_of "$TMP_DIR/$ZIP_NAME") \
        || die "failed to compute the SHA-256 of $ZIP_NAME"

    if [ -z "$expected" ]; then
        die "no expected SHA-256 available for $ZIP_NAME — refusing to install.
After checking it against https://gradle.org/release-checksums/ , add:
    distributionSha256Sum=$actual
to gradle/wrapper/gradle-wrapper.properties."
    fi

    if [ "$actual" != "$expected" ]; then
        die "SHA-256 mismatch for $ZIP_NAME
  expected: $expected
  actual:   $actual
Refusing to install — this is what a tampered or corrupted download looks like."
    fi
    info "SHA-256 verified: $actual"

    info "extracting..."
    mkdir -p "$TMP_DIR/x"
    unzip -q "$TMP_DIR/$ZIP_NAME" -d "$TMP_DIR/x"
    [ -x "$TMP_DIR/x/$DIST_NAME/bin/gradle" ] \
        || die "unexpected archive layout: $DIST_NAME/bin/gradle not found in $ZIP_NAME"

    # Stage a sibling directory, then swap it in, so a partial extraction is
    # never visible under INSTALL_DIR. A concurrent run that won the race is
    # fine — its tree is equivalent.
    if [ ! -x "$GRADLE_BIN" ]; then
        mkdir -p "$(dirname "$INSTALL_DIR")"
        staging="$INSTALL_DIR.new.$$"
        rm -rf "$staging"
        mv "$TMP_DIR/x" "$staging"
        rm -rf "$INSTALL_DIR"
        mv "$staging" "$INSTALL_DIR"
    fi
    info "$DIST_NAME ready."

    rm -rf "$TMP_DIR"
    trap - EXIT HUP INT TERM
fi

exec "$GRADLE_BIN" "$@"
