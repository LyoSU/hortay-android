#!/usr/bin/env bash
# Minimal ffmpeg for VP9+alpha WebM decode only — vendored into :libwebm.
# Android/MediaCodec can't decode VP9 alpha (androidx/media#1388); this software path can.
#
# Builds on the HOST using the Android NDK (no Docker): ffmpeg cross-compiles for Android
# cleanly from a macOS/Linux host, and the official NDK ships only host-native (x86_64/darwin)
# toolchains — so a host build avoids the qemu/Rosetta emulation that a Linux-x86_64 NDK would
# need inside an arm64 container. Output: static libs in libwebm/src/main/jniLibs/<abi>/ and
# headers in libwebm/src/main/cpp/include-<abi>/ (both gitignored). Pin written to ffmpeg-version.txt.
#
# Requires: git, make, and the Android NDK. Install the NDK with:
#   sdkmanager "ndk;27.2.12479018"
set -euo pipefail
FFMPEG_REF="${1:-n7.1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# --- locate the NDK -----------------------------------------------------------
find_ndk() {
  if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then echo "$ANDROID_NDK_HOME"; return; fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}"
  if [ -d "$sdk/ndk" ]; then ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1; return; fi
}
NDK="$(find_ndk)"
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "ERROR: Android NDK not found. Install: sdkmanager \"ndk;27.2.12479018\""; exit 1; }
case "$(uname -s)" in Darwin) HOST_TAG=darwin-x86_64 ;; Linux) HOST_TAG=linux-x86_64 ;; *) echo "unsupported host"; exit 1 ;; esac
TOOL="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -x "$TOOL/clang" ] || { echo "ERROR: NDK toolchain missing at $TOOL"; exit 1; }
echo "Using NDK: $NDK ($HOST_TAG)"

# --- fetch ffmpeg -------------------------------------------------------------
WORK="$ROOT/build/ffmpeg-src"
mkdir -p "$WORK"
if [ ! -d "$WORK/ffmpeg/.git" ]; then
  git clone --depth 1 --branch "$FFMPEG_REF" https://github.com/FFmpeg/FFmpeg.git "$WORK/ffmpeg"
fi

build_abi() {
  local ABI="$1" TRIPLE="$2" CPU="$3"
  echo "=== building ffmpeg for $ABI ==="
  local OUT="$WORK/out-$ABI" BLD="$WORK/bld-$ABI"
  rm -rf "$BLD"; mkdir -p "$BLD"; ( cd "$BLD"
    "$WORK/ffmpeg/configure" \
      --target-os=android --arch="$CPU" --enable-cross-compile \
      --cc="$TOOL/${TRIPLE}21-clang" --cxx="$TOOL/${TRIPLE}21-clang++" \
      --ar="$TOOL/llvm-ar" --ranlib="$TOOL/llvm-ranlib" --strip="$TOOL/llvm-strip" --nm="$TOOL/llvm-nm" \
      --disable-everything --disable-programs --disable-doc --disable-avdevice --disable-postproc \
      --enable-avformat --enable-avcodec --enable-avutil --enable-swscale \
      --enable-decoder=vp9 --enable-parser=vp9 \
      --enable-demuxer=matroska --enable-protocol=file \
      --enable-static --disable-shared --enable-pic \
      --prefix="$OUT"
    make -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
    make install
  )
  mkdir -p "$ROOT/libwebm/src/main/jniLibs/$ABI"
  cp "$OUT"/lib/*.a "$ROOT/libwebm/src/main/jniLibs/$ABI/"
  rm -rf "$ROOT/libwebm/src/main/cpp/include-$ABI"
  mkdir -p "$ROOT/libwebm/src/main/cpp"
  cp -R "$OUT/include" "$ROOT/libwebm/src/main/cpp/include-$ABI"
}

build_abi arm64-v8a   aarch64-linux-android arm64
build_abi armeabi-v7a armv7a-linux-androideabi arm
echo "$FFMPEG_REF" > "$ROOT/scripts/ffmpeg-version.txt"
echo "ffmpeg $FFMPEG_REF built into libwebm/src/main/jniLibs/"
