#!/usr/bin/env bash
# Minimal ffmpeg + libvpx for Telegram WebM (VP9 + alpha) decode only — vendored into :libwebm.
# Android/MediaCodec can't decode the VP9 alpha plane (androidx/media#1388). Telegram stores the
# alpha as a separate VP9 stream in the WebM BlockAdditional; ONLY libvpx reconstructs it as
# yuva420p (ffmpeg's native vp9 decoder drops it -> opaque square). So we must build libvpx and
# enable ffmpeg's libvpx_vp9 decoder.
#
# Builds on the HOST via the Android NDK (no Docker): the official NDK ships host-native
# toolchains only, so a host cross-compile avoids the qemu emulation a Linux-x86_64 NDK needs in
# an arm64 container. Output: static .a in libwebm/src/main/jniLibs/<abi>/ + headers in
# libwebm/src/main/cpp/include-<abi>/ (both gitignored). Pins written to ffmpeg-version.txt.
#
# Requires: git, make, and the Android NDK (sdkmanager "ndk;27.2.12479018").
set -euo pipefail
FFMPEG_REF="${1:-n7.1}"
VPX_REF="${2:-v1.14.1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

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
NPROC="$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
echo "Using NDK: $NDK ($HOST_TAG)"

WORK="$ROOT/build/ffmpeg-src"
mkdir -p "$WORK"
[ -d "$WORK/libvpx/.git" ] || git clone --depth 1 --branch "$VPX_REF" https://chromium.googlesource.com/webm/libvpx "$WORK/libvpx"
[ -d "$WORK/ffmpeg/.git" ] || git clone --depth 1 --branch "$FFMPEG_REF" https://github.com/FFmpeg/FFmpeg.git "$WORK/ffmpeg"

build_abi() {
  local ABI="$1" TRIPLE="$2" FF_ARCH="$3" VPX_TARGET="$4"
  local OUT="$WORK/out-$ABI"
  rm -rf "$OUT"
  local CC="$TOOL/${TRIPLE}21-clang"

  echo "=== libvpx for $ABI ==="
  # All archive tools must be the NDK llvm-* ones — otherwise libvpx runs the host macOS
  # ranlib/strip on the ELF objects ("not a mach-o file") and ships an empty libvpx.a.
  # --enable-runtime-cpu-detect (below) is MANDATORY, not a perf knob. With RTCD *disabled*
  # libvpx has no dispatch tables: each kernel is #define'd statically at compile time to the
  # highest ISA extension the NDK clang could assemble. Modern clang assembles ARMv8.6 i8mm, so
  # vpx_convolve8 et al. bind to *_neon_i8mm unconditionally — and run with ZERO runtime CPU
  # check. On any pre-ARMv8.6 arm64 device (e.g. Snapdragon 855 / Cortex-A76, ARMv8.2) that's an
  # illegal opcode -> SIGILL crash inside vp9_decode_frame the moment a WebM(VP9+alpha)
  # sticker/emoji decodes (issue #2). RTCD's aarch64 path reads getauxval(AT_HWCAP2)&I8MM and
  # only takes the i8mm/dotprod/sve kernels when the CPU actually has them, so it both fixes the
  # crash AND keeps the acceleration on capable chips. DO NOT switch back to
  # --disable-runtime-cpu-detect.
  #
  # NB: never insert a comment between the CC=... "\"-continued env-prefix and the configure
  # call below. The backslash splices the comment onto the assignment line, the toolchain vars
  # stop being a command prefix (they're set but unexported), configure runs without them and
  # silently falls back to the host gcc/g++ -> "unable to link executables" (crt0.o not found).
  local VBLD="$WORK/vbld-$ABI"; rm -rf "$VBLD"; mkdir -p "$VBLD"; ( cd "$VBLD"
    CC="$CC" CXX="$TOOL/${TRIPLE}21-clang++" LD="$CC" AS="$CC" \
    AR="$TOOL/llvm-ar" NM="$TOOL/llvm-nm" RANLIB="$TOOL/llvm-ranlib" STRIP="$TOOL/llvm-strip" \
    "$WORK/libvpx/configure" --target="$VPX_TARGET" \
      --disable-examples --disable-tools --disable-docs --disable-unit-tests \
      --enable-vp9-decoder --disable-vp9-encoder --disable-vp8 \
      --enable-static --disable-shared --enable-pic --enable-runtime-cpu-detect \
      --prefix="$OUT"
    make -j"$NPROC" && make install
  )

  echo "=== ffmpeg for $ABI ==="
  local FBLD="$WORK/fbld-$ABI"; rm -rf "$FBLD"; mkdir -p "$FBLD"; ( cd "$FBLD"
    "$WORK/ffmpeg/configure" \
      --target-os=android --arch="$FF_ARCH" --enable-cross-compile \
      --cc="$CC" --cxx="$TOOL/${TRIPLE}21-clang++" \
      --ar="$TOOL/llvm-ar" --ranlib="$TOOL/llvm-ranlib" --strip="$TOOL/llvm-strip" --nm="$TOOL/llvm-nm" \
      --disable-everything --disable-programs --disable-doc --disable-avdevice --disable-postproc \
      --enable-avformat --enable-avcodec --enable-avutil --enable-swscale \
      --enable-libvpx --enable-decoder=libvpx_vp9 --enable-decoder=vp9 --enable-parser=vp9 \
      --enable-demuxer=matroska --enable-protocol=file \
      --enable-static --disable-shared --enable-pic \
      --extra-cflags="-I$OUT/include" --extra-ldflags="-L$OUT/lib" \
      --prefix="$OUT"
    make -j"$NPROC" && make install
  )

  mkdir -p "$ROOT/libwebm/src/main/jniLibs/$ABI"
  cp "$OUT"/lib/*.a "$ROOT/libwebm/src/main/jniLibs/$ABI/"   # ffmpeg libs + libvpx.a
  rm -rf "$ROOT/libwebm/src/main/cpp/include-$ABI"
  mkdir -p "$ROOT/libwebm/src/main/cpp"
  cp -R "$OUT/include" "$ROOT/libwebm/src/main/cpp/include-$ABI"
}

build_abi arm64-v8a   aarch64-linux-android   arm64 arm64-android-gcc
# TODO(armv7): libvpx's armv7 NEON .asm path mis-links under NDK clang ("undefined symbol: main"
# on *.asm.S.o). Until that's resolved, ship arm64-v8a only; 32-bit devices fall back to the
# static sticker thumbnail (WebmAlphaDecoder degrades gracefully when libhortaywebm.so is absent).
# build_abi armeabi-v7a armv7a-linux-androideabi arm   armv7-android-gcc
echo "$FFMPEG_REF (libvpx $VPX_REF)" > "$ROOT/scripts/ffmpeg-version.txt"
echo "ffmpeg $FFMPEG_REF + libvpx $VPX_REF built into libwebm/src/main/jniLibs/"
