#!/usr/bin/env bash
#
# Parallel ABI build of TDLib for Android.
# Forked from tdlib/td/example/android/build-tdlib.sh — same args, same output layout,
# but the per-ABI loop runs concurrently and only the ABIs we actually ship are built.
#
# Args:
#   $1 ANDROID_SDK_ROOT       (default: SDK)
#   $2 ANDROID_NDK_VERSION    (default: 23.2.8568313)
#   $3 OPENSSL_INSTALL_DIR    (default: third-party/openssl)
#   $4 ANDROID_STL            (default: c++_static)

set -euo pipefail

ANDROID_SDK_ROOT=${1:-SDK}
ANDROID_NDK_VERSION=${2:-23.2.8568313}
OPENSSL_INSTALL_DIR=${3:-third-party/openssl}
ANDROID_STL=${4:-c++_static}

# ABIs to build. Match app/build.gradle.kts abiFilters.
# Override at call site: ABIS="arm64-v8a armeabi-v7a x86_64 x86" ./build-tdlib-parallel.sh ...
ABIS=${ABIS:-"arm64-v8a x86_64"}

if [ "$ANDROID_STL" != "c++_static" ] && [ "$ANDROID_STL" != "c++_shared" ] ; then
  echo 'Error: ANDROID_STL must be either "c++_static" or "c++_shared".'
  exit 1
fi

# Caller is expected to have already chdir'd into the upstream
# example/android dir (where check-environment.sh lives).
# shellcheck disable=SC1091
source ./check-environment.sh

if [ ! -d "$ANDROID_SDK_ROOT" ] ; then
  echo "Error: \"$ANDROID_SDK_ROOT\" doesn't exist."
  exit 1
fi
if [ ! -d "$OPENSSL_INSTALL_DIR" ] ; then
  echo "Error: \"$OPENSSL_INSTALL_DIR\" doesn't exist."
  exit 1
fi

ANDROID_SDK_ROOT="$(cd "$(dirname -- "$ANDROID_SDK_ROOT")" >/dev/null; pwd -P)/$(basename -- "$ANDROID_SDK_ROOT")"
ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION"
OPENSSL_INSTALL_DIR="$(cd "$(dirname -- "$OPENSSL_INSTALL_DIR")" >/dev/null; pwd -P)/$(basename -- "$OPENSSL_INSTALL_DIR")"
PATH="$ANDROID_SDK_ROOT/cmake/3.22.1/bin:$PATH"

echo "==> Generating TDLib source files (single-threaded, required before ABI builds)..."
mkdir -p build-native-Java
( cd build-native-Java && cmake -DTD_GENERATE_SOURCE_FILES=ON .. && cmake --build . )

rm -rf tdlib

echo "==> Generating Java sources (Java interface)..."
rm -f android.jar annotation-1.4.0.jar
$WGET https://maven.google.com/androidx/annotation/annotation/1.4.0/annotation-1.4.0.jar
cmake --build build-native-Java --target tl_generate_java
php AddIntDef.php org/drinkless/tdlib/TdApi.java
mkdir -p tdlib/java/org/drinkless/tdlib
cp -p ../java/org/drinkless/tdlib/Client.java tdlib/java/org/drinkless/tdlib/Client.java
mv org/drinkless/tdlib/TdApi.java tdlib/java/org/drinkless/tdlib/TdApi.java
rm -rf org

build_one_abi() {
  local ABI=$1
  local LOG="build-${ABI}.log"
  echo "==> [${ABI}] starting (log: ${LOG})"
  mkdir -p "tdlib/libs/$ABI/"
  mkdir -p "build-${ABI}-Java"
  (
    cd "build-${ABI}-Java"
    cmake \
      -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
      -DOPENSSL_ROOT_DIR="$OPENSSL_INSTALL_DIR/$ABI" \
      -DCMAKE_BUILD_TYPE=RelWithDebInfo \
      -GNinja \
      -DANDROID_ABI="$ABI" \
      -DANDROID_STL="$ANDROID_STL" \
      -DANDROID_PLATFORM=android-16 \
      ..
    cmake --build . --target tdjni
    cp -p libtd*.so* "../tdlib/libs/$ABI/"
  ) >"$LOG" 2>&1

  if [ "$ANDROID_STL" = "c++_shared" ] ; then
    case "$ABI" in
      arm64-v8a)    FULL_ABI="aarch64-linux-android" ;;
      armeabi-v7a)  FULL_ABI="arm-linux-androideabi" ;;
      x86_64)       FULL_ABI="x86_64-linux-android" ;;
      x86)          FULL_ABI="i686-linux-android" ;;
    esac
    cp "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST_ARCH/sysroot/usr/lib/$FULL_ABI/libc++_shared.so" "tdlib/libs/$ABI/"
    "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST_ARCH/bin/llvm-strip" "tdlib/libs/$ABI/libc++_shared.so"
  fi
  if [ -e "$OPENSSL_INSTALL_DIR/$ABI/lib/libcrypto.so" ] ; then
    cp "$OPENSSL_INSTALL_DIR/$ABI/lib/libcrypto.so" "$OPENSSL_INSTALL_DIR/$ABI/lib/libssl.so" "tdlib/libs/$ABI/"
    "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST_ARCH/bin/llvm-strip" "tdlib/libs/$ABI/libcrypto.so"
    "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST_ARCH/bin/llvm-strip" "tdlib/libs/$ABI/libssl.so"
  fi
  echo "==> [${ABI}] done"
}

# Run ABIs in parallel. Each ninja invocation already uses all cores; running
# two ABIs concurrently overlaps their CPU-bound and I/O-bound phases.
echo "==> Building ABIs in parallel: ${ABIS}"
pids=()
for ABI in $ABIS ; do
  build_one_abi "$ABI" &
  pids+=($!)
done

# Wait for all background builds; surface the first failure.
fail=0
for pid in "${pids[@]}" ; do
  if ! wait "$pid" ; then
    fail=1
  fi
done
if [ "$fail" -ne 0 ] ; then
  echo "==> One or more ABI builds failed. See build-*.log."
  for log in build-*.log ; do
    echo "----- $log -----"
    tail -n 40 "$log"
  done
  exit 1
fi

echo "==> Compressing artifacts..."
rm -f tdlib.zip tdlib-debug.zip
jar -cMf tdlib-debug.zip tdlib
rm -rf tdlib/libs/*/*.debug 2>/dev/null || true
jar -cMf tdlib.zip tdlib
mv tdlib.zip tdlib-debug.zip tdlib

echo "==> Done. Artifacts in $(pwd)/tdlib/"
