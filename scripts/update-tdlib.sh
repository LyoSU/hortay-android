#!/usr/bin/env bash
#
# Build TDLib from upstream and install it into the :libtdlib module.
#
# Usage:
#   ./scripts/update-tdlib.sh              # default: upstream master
#   ./scripts/update-tdlib.sh master       # explicit master
#   ./scripts/update-tdlib.sh 8fc2344f     # specific commit
#   ./scripts/update-tdlib.sh v1.8.0       # tag (note: TDLib's tags are stale —
#                                          #       upstream ships off master)
#
# Env knobs:
#   ANDROID_NDK_VERSION  — NDK to use inside the container (default: 23.2.8568313)
#   OPENSSL_VERSION      — OpenSSL to embed (default: OpenSSL_1_1_1w)
#   ABIS                 — space-separated ABI list (default: "arm64-v8a x86_64")
#   KEEP_DEBUG           — set to 1 to also keep tdlib-debug.zip alongside the AAR
#
# What it does:
#   1. Resolves the requested upstream ref to a concrete commit SHA.
#   2. Runs the multi-stage Dockerfile (BuildKit) to produce tdlib.zip + tdlib-debug.zip.
#   3. Unpacks artifacts into libtdlib/src/main/{java,jniLibs}.
#   4. Records the resolved ref + SHA + timestamp in scripts/tdlib-version.txt.
#
# The first run takes ~30 minutes (SDK + OpenSSL build). Subsequent bumps that
# only change TDLIB_REF reuse the cached layers and finish in ~10–15 minutes.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null && pwd -P)"
BUILDER_DIR="$REPO_ROOT/scripts/tdlib-builder"
LIBTDLIB_DIR="$REPO_ROOT/libtdlib"
VERSION_FILE="$REPO_ROOT/scripts/tdlib-version.txt"

TDLIB_REF="${1:-}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:-23.2.8568313}"
OPENSSL_VERSION="${OPENSSL_VERSION:-OpenSSL_1_1_1w}"
ABIS="${ABIS:-arm64-v8a x86_64}"
KEEP_DEBUG="${KEEP_DEBUG:-0}"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m==> warn:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m==> error:\033[0m %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || die "docker not found in PATH"
command -v jq     >/dev/null 2>&1 || warn "jq not found — version-resolution output will be uglier"
command -v unzip  >/dev/null 2>&1 || die "unzip not found in PATH"

# ---------------------------------------------------------------------------
# Resolve TDLIB_REF.
# Default: master. TDLib's git tags are years out of date (last is v1.8.0 from 2022);
# every real release ships from master, with the version baked into CMakeLists.txt.
if [ -z "$TDLIB_REF" ] ; then
  TDLIB_REF=master
  log "No ref given — defaulting to master"
fi

log "Resolving $TDLIB_REF to a commit SHA..."
RESOLVED_SHA=$(curl -fsSL "https://api.github.com/repos/tdlib/td/commits/$TDLIB_REF" \
  | sed -n 's/.*"sha": *"\([^"]*\)".*/\1/p' | head -n1)
[ -n "$RESOLVED_SHA" ] || die "could not resolve $TDLIB_REF to a commit SHA"
log "Resolved $TDLIB_REF -> $RESOLVED_SHA"

# ---------------------------------------------------------------------------
# Build inside a temporary output dir.
WORKDIR="$(mktemp -d -t tdlib-build-XXXXXX)"
trap 'rm -rf "$WORKDIR"' EXIT
log "Output staging dir: $WORKDIR"

log "Running docker build (this is the long step)..."
DOCKER_BUILDKIT=1 docker build \
  --file "$BUILDER_DIR/Dockerfile" \
  --output "type=local,dest=$WORKDIR" \
  --build-arg "TDLIB_REF=$RESOLVED_SHA" \
  --build-arg "ANDROID_NDK_VERSION=$ANDROID_NDK_VERSION" \
  --build-arg "OPENSSL_VERSION=$OPENSSL_VERSION" \
  --target export \
  "$BUILDER_DIR"

# ---------------------------------------------------------------------------
# Unpack artifacts.
[ -f "$WORKDIR/tdlib.zip" ] || die "tdlib.zip missing in build output"

log "Unpacking artifacts into :libtdlib..."
EXTRACT_DIR="$WORKDIR/extracted"
mkdir -p "$EXTRACT_DIR"
unzip -qq "$WORKDIR/tdlib.zip" -d "$EXTRACT_DIR"

# Layout from upstream:
#   tdlib/java/org/drinkless/tdlib/{Client,TdApi}.java
#   tdlib/libs/<abi>/libtdjni.so

JAVA_SRC="$EXTRACT_DIR/tdlib/java/org/drinkless/tdlib"
LIBS_SRC="$EXTRACT_DIR/tdlib/libs"
JAVA_DST="$LIBTDLIB_DIR/src/main/java/org/drinkless/tdlib"
LIBS_DST="$LIBTDLIB_DIR/src/main/jniLibs"

[ -f "$JAVA_SRC/TdApi.java" ] || die "TdApi.java missing in artifact"
[ -f "$JAVA_SRC/Client.java" ] || die "Client.java missing in artifact"

mkdir -p "$JAVA_DST"
cp -f "$JAVA_SRC/TdApi.java"  "$JAVA_DST/TdApi.java"
cp -f "$JAVA_SRC/Client.java" "$JAVA_DST/Client.java"

# Wipe old .so files so a removed ABI does not silently linger.
rm -rf "$LIBS_DST"/*/
for ABI in $ABIS ; do
  if [ ! -d "$LIBS_SRC/$ABI" ] ; then
    warn "ABI $ABI not present in artifact (skipped)"
    continue
  fi
  mkdir -p "$LIBS_DST/$ABI"
  cp -f "$LIBS_SRC/$ABI"/*.so "$LIBS_DST/$ABI/"
done

if [ "$KEEP_DEBUG" = "1" ] && [ -f "$WORKDIR/tdlib-debug.zip" ] ; then
  cp -f "$WORKDIR/tdlib-debug.zip" "$REPO_ROOT/scripts/tdlib-debug-${RESOLVED_SHA:0:10}.zip"
  log "Kept debug bundle at scripts/tdlib-debug-${RESOLVED_SHA:0:10}.zip"
fi

# ---------------------------------------------------------------------------
# Record what we just installed.
TDLIB_PROJECT_VERSION=$(grep -E "project\(TDLib VERSION" "$EXTRACT_DIR/tdlib/java"/../../../../tdlib/javadoc/overview-summary.html 2>/dev/null \
  | sed -n 's/.*VERSION \([0-9.]*\).*/\1/p' | head -n1 || true)
# javadoc is a fragile source — fall back to scraping the public CMakeLists.
if [ -z "$TDLIB_PROJECT_VERSION" ] ; then
  TDLIB_PROJECT_VERSION=$(curl -fsSL "https://raw.githubusercontent.com/tdlib/td/$RESOLVED_SHA/CMakeLists.txt" \
    | sed -n 's/.*project(TDLib VERSION \([0-9.]*\).*/\1/p' | head -n1 || true)
fi

cat > "$VERSION_FILE" <<EOF
# Generated by scripts/update-tdlib.sh — do not edit by hand.
TDLIB_VERSION=${TDLIB_PROJECT_VERSION:-unknown}
TDLIB_REF=$TDLIB_REF
TDLIB_SHA=$RESOLVED_SHA
ANDROID_NDK_VERSION=$ANDROID_NDK_VERSION
OPENSSL_VERSION=$OPENSSL_VERSION
ABIS=$ABIS
UPDATED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

log "Installed:"
sed 's/^/    /' "$VERSION_FILE"
log "Done. Run ./gradlew :app:assembleDebug to verify."
