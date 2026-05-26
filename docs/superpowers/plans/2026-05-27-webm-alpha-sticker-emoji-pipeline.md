# WebM (VP9+alpha) Sticker & Emoji Pipeline — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render Telegram WebM (VP9 `yuva420p`) video stickers and custom emoji with their real alpha channel — and animated, not as a flat square or a static thumbnail — by introducing a minimal native ffmpeg software decoder and a shared, frame-cached Compose renderer.

**Architecture:** Android/MediaCodec cannot decode VP9 alpha (androidx/media #1388), so the hardware ExoPlayer path produces opaque squares. We ship a *minimal* ffmpeg build (vp9 decoder + matroska/webm demuxer + swscale→RGBA only) in a new `:libwebm` module, fronted by a thin JNI decoder that emits RGBA frames. Decode is **decoupled from render**: a process-wide `WebmFrameCache` (LRU, keyed by `(key, sizePx)`) decodes each short loop once off-thread; a single `WebmAnimationClock` ticks all visible instances; Compose draws the current `ImageBitmap` frame on a `Canvas` (native alpha, no `SurfaceView`, no per-instance `ExoPlayer`). The same pipeline serves stickers, inline custom emoji, animated reactions and emoji-status — only the codec build stays minimal.

**Tech Stack:** ffmpeg (minimal, static) + Android NDK via Docker; C JNI bridge; Kotlin/Coroutines; Jetpack Compose `Canvas`/`ImageBitmap`; existing `MediaCache` for file download; mirrors the `:libtdlib` vendoring + `scripts/update-tdlib.sh` build convention.

**Scope discipline (from brainstorming):** Maximize reuse of the *pipeline* across every alpha-WebM surface; do NOT broaden the ffmpeg *build* beyond vp9+webm+swscale. Hardware-decodable media (H.264/HEVC/opaque-VP9 video, GIF→MP4, round video, AAC/MP3/Opus/Ogg audio) stays on ExoPlayer/MediaCodec — software-decoding it would regress battery/thermal/CPU.

---

## File Structure

**New module `:libwebm`** (mirrors `:libtdlib` — vendored native artifacts, thin Kotlin surface):
- `libwebm/build.gradle.kts` — Android library, `ndkVersion`, `abiFilters [arm64-v8a, armeabi-v7a]`, packages `jniLibs`.
- `libwebm/src/main/cpp/webm_alpha_decoder.c` — C decoder using ffmpeg libav* APIs; JNI entry points.
- `libwebm/src/main/cpp/CMakeLists.txt` — links the static ffmpeg libs into `libhortaywebm.so`.
- `libwebm/src/main/jniLibs/<abi>/*.a` — vendored ffmpeg build output (gitignored, like libtdjni).
- `libwebm/src/main/java/dev/lyo/hortay/webm/WebmAlphaNative.kt` — JNI declarations.

**Build tooling:**
- `scripts/build-ffmpeg.sh` — Docker + NDK minimal ffmpeg build → `libwebm/src/main/jniLibs/`.
- `scripts/ffmpeg-version.txt` — pinned ffmpeg ref (auto-written by the script).
- `docker/ffmpeg/Dockerfile` — build image (NDK + ffmpeg source checkout).

**`:app` — decode/cache/clock (`app/src/main/kotlin/dev/lyo/hortay/data/media/`):**
- `WebmAlphaDecoder.kt` — Kotlin wrapper over `WebmAlphaNative`; `decode(path, sizePx): DecodedWebm`.
- `DecodedWebm.kt` — `@Immutable` value type: frames (`List<ImageBitmap>`), per-frame delays, intrinsic size.
- `WebmFrameCache.kt` — process singleton; LRU(byte-bounded); off-thread decode; `StateFlow` `observe(key,path)`.

**`:app` — render (`app/src/main/kotlin/dev/lyo/hortay/ui/media/`):**
- `WebmAnimationClock.kt` — single Choreographer-driven tick; advances all registered animations; CompositionLocal `LocalWebmClock`.
- `WebmAlphaImage.kt` — `@Composable` drawing the current frame; observes cache + clock.
- `LocalWebmFrameCache.kt` — heavy-singleton CompositionLocal.

**`:app` — rewiring (modify):**
- `ui/media/WebmStickerPlayer.kt` — swap ExoPlayer/TextureView body for `WebmAlphaImage`.
- `ui/media/CustomEmojiInlineView.kt:177-233` — WebM branch animates via the shared cache.
- `ui/media/ExoPlayerPool.kt` — KDoc note: ExoPlayer is no longer used for WebM (MP4/round only).
- `AppGraph.kt`, `MainActivity.kt`, `ARCHITECTURE.md`, `CHANGELOG.md`, `config/detekt/baseline.xml`.

**DI:** `AppGraph` gains `webmFrameCache: WebmFrameCache`; injected via `LocalWebmFrameCache` (heavy singleton, like `LocalMediaCache`).

---

## Phase 0 — Module + build pipeline

### Task 0.1: Create the `:libwebm` Android library module

**Files:**
- Create: `libwebm/build.gradle.kts`
- Modify: `settings.gradle.kts` (add `include(":libwebm")`)
- Create: `libwebm/src/main/AndroidManifest.xml` (empty `<manifest/>` like `:libtdlib`)
- Create: `libwebm/.gitignore` (`/src/main/jniLibs/`, `/build/`)

- [ ] **Step 1: Add the module include**

In `settings.gradle.kts`, after the existing `:libtdlib` include:
```kotlin
include(":libwebm")
```

- [ ] **Step 2: Write `libwebm/build.gradle.kts`** (match `:libtdlib` toolchain — JVM 17, compileSdk/minSdk from the version catalog)

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.lyo.hortay.webm"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { cmake { cppFlags += "-std=c11" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

- [ ] **Step 3: Wire the dependency in `:app`**

In `app/build.gradle.kts` dependencies (next to the `:libtdlib` project dep):
```kotlin
implementation(project(":libwebm"))
```

- [ ] **Step 4: Verify Gradle sees the module**

Run: `./gradlew :libwebm:tasks -q`
Expected: task list prints, no "project not found".

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts libwebm/build.gradle.kts libwebm/src/main/AndroidManifest.xml libwebm/.gitignore app/build.gradle.kts
git commit -m "build(webm): scaffold :libwebm native module"
```

### Task 0.2: Minimal ffmpeg build script (Docker + NDK)

**Files:**
- Create: `docker/ffmpeg/Dockerfile`
- Create: `scripts/build-ffmpeg.sh`
- Create: `scripts/ffmpeg-version.txt` (written by the script)

- [ ] **Step 1: Write `docker/ffmpeg/Dockerfile`**

```dockerfile
FROM ubuntu:24.04
ARG NDK_VERSION=r27c
RUN apt-get update && apt-get install -y curl unzip make pkg-config git yasm && rm -rf /var/lib/apt/lists/*
RUN curl -L -o /tmp/ndk.zip https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip \
 && unzip -q /tmp/ndk.zip -d /opt && rm /tmp/ndk.zip
ENV ANDROID_NDK_HOME=/opt/android-ndk-${NDK_VERSION}
WORKDIR /work
```

- [ ] **Step 2: Write `scripts/build-ffmpeg.sh`** — clones ffmpeg, configures the *minimal* feature set, builds static libs per ABI, copies into `:libwebm`

```bash
#!/usr/bin/env bash
# Minimal ffmpeg for VP9+alpha WebM decode only. Mirrors scripts/update-tdlib.sh:
# Docker build, vendored output under libwebm/src/main/jniLibs/<abi>/, version pinned.
set -euo pipefail
FFMPEG_REF="${1:-n7.1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMG=hortay-ffmpeg-build
docker build -t "$IMG" "$ROOT/docker/ffmpeg"

build_abi() {
  local ABI="$1" TRIPLE="$2" CPU="$3"
  docker run --rm -v "$ROOT:/host" "$IMG" bash -lc "
    set -e
    cd /work && rm -rf ffmpeg && git clone --depth 1 --branch ${FFMPEG_REF} https://github.com/FFmpeg/FFmpeg.git ffmpeg && cd ffmpeg
    TOOL=\$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
    ./configure \
      --target-os=android --arch=${CPU} --enable-cross-compile \
      --cc=\$TOOL/${TRIPLE}21-clang --cxx=\$TOOL/${TRIPLE}21-clang++ \
      --ar=\$TOOL/llvm-ar --ranlib=\$TOOL/llvm-ranlib --strip=\$TOOL/llvm-strip --nm=\$TOOL/llvm-nm \
      --disable-everything --disable-programs --disable-doc --disable-avdevice --disable-postproc \
      --enable-avformat --enable-avcodec --enable-avutil --enable-swscale \
      --enable-decoder=vp9 --enable-parser=vp9 \
      --enable-demuxer=matroska --enable-protocol=file \
      --enable-static --disable-shared --enable-pic \
      --prefix=/work/out-${ABI}
    make -j\"\$(nproc)\" && make install
    mkdir -p /host/libwebm/src/main/jniLibs/${ABI}
    cp /work/out-${ABI}/lib/*.a /host/libwebm/src/main/jniLibs/${ABI}/
    rm -rf /host/libwebm/src/main/cpp/include-${ABI} && cp -r /work/out-${ABI}/include /host/libwebm/src/main/cpp/include-${ABI}
  "
}
build_abi arm64-v8a   aarch64-linux-android arm64
build_abi armeabi-v7a armv7a-linux-androideabi arm
echo "$FFMPEG_REF" > "$ROOT/scripts/ffmpeg-version.txt"
echo "ffmpeg ${FFMPEG_REF} built into libwebm/src/main/jniLibs/"
```

> NOTE for implementer: ffmpeg headers are ABI-independent except `config.h`. After the first build, diff `include-arm64-v8a` vs `include-armeabi-v7a`; if identical except `config.h`, collapse to one shared `include/` + per-ABI `config.h` and point CMake at both. If `configure` fails to link later, the usual missing flags are `--enable-bsf=vp9_superframe_split` and `--enable-parser=vp9` (already present) — add bitstream filters as the linker complains.

- [ ] **Step 3: Make executable + run the build**

Run: `chmod +x scripts/build-ffmpeg.sh && ./scripts/build-ffmpeg.sh n7.1`
Expected: completes (~15–30 min first run); `libwebm/src/main/jniLibs/arm64-v8a/libavcodec.a` (+ libavformat/libavutil/libswscale) exist.

- [ ] **Step 4: Verify binary footprint**

Run: `du -sh libwebm/src/main/jniLibs/arm64-v8a`
Expected: low single-digit MB. If much larger, tighten `--disable-*` (no encoders/filters/network).

- [ ] **Step 5: Commit (script + pin only; jniLibs gitignored like libtdjni)**

```bash
git add docker/ffmpeg/Dockerfile scripts/build-ffmpeg.sh scripts/ffmpeg-version.txt
git commit -m "build(webm): minimal ffmpeg (vp9+webm+swscale) Docker/NDK pipeline"
```

---

## Phase 1 — Native JNI decoder

### Task 1.1: C decoder + JNI bridge

**Files:**
- Create: `libwebm/src/main/cpp/webm_alpha_decoder.c`
- Create: `libwebm/src/main/cpp/CMakeLists.txt`
- Create: `libwebm/src/main/java/dev/lyo/hortay/webm/WebmAlphaNative.kt`

- [ ] **Step 1: Write `CMakeLists.txt`** linking the static ffmpeg libs

```cmake
cmake_minimum_required(VERSION 3.22)
project(hortaywebm C)
set(ABI ${ANDROID_ABI})
set(FF ${CMAKE_SOURCE_DIR}/../jniLibs/${ABI})
add_library(hortaywebm SHARED webm_alpha_decoder.c)
target_include_directories(hortaywebm PRIVATE ${CMAKE_SOURCE_DIR}/include-${ABI})
# Static ffmpeg archives. Link order matters: avformat -> avcodec -> swscale -> avutil.
foreach(lib avformat avcodec swscale avutil)
  add_library(${lib} STATIC IMPORTED)
  set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${FF}/lib${lib}.a)
endforeach()
find_library(log-lib log)
target_link_libraries(hortaywebm avformat avcodec swscale avutil ${log-lib} z)
```

- [ ] **Step 2: Write `webm_alpha_decoder.c`** — open, decode-all-to-RGBA, free. Returns a flat ARGB_8888-packed `int[]` plus per-frame delays.

```c
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>

typedef struct { int *pixels; int *delays; int count; int w; int h; } Decoded;

static void free_decoded(Decoded *d) {
    if (!d) return; free(d->pixels); free(d->delays); free(d);
}

// Returns malloc'd Decoded* or NULL. outW/outH<=0 means intrinsic size.
static Decoded *decode_all(const char *path, int outW, int outH) {
    AVFormatContext *fmt = NULL;
    if (avformat_open_input(&fmt, path, NULL, NULL) < 0) return NULL;
    if (avformat_find_stream_info(fmt, NULL) < 0) { avformat_close_input(&fmt); return NULL; }
    int vs = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (vs < 0) { avformat_close_input(&fmt); return NULL; }
    AVStream *st = fmt->streams[vs];
    const AVCodec *codec = avcodec_find_decoder(st->codecpar->codec_id);
    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    avcodec_parameters_to_context(ctx, st->codecpar);
    if (avcodec_open2(ctx, codec, NULL) < 0) { avcodec_free_context(&ctx); avformat_close_input(&fmt); return NULL; }

    int W = outW > 0 ? outW : ctx->width;
    int H = outH > 0 ? outH : ctx->height;
    struct SwsContext *sws = NULL; // created once the source pix_fmt is known
    int cap = 16, count = 0;
    int *pixels = NULL; int *delays = malloc(sizeof(int) * cap);
    size_t frameStride = (size_t)W * H;

    AVPacket *pkt = av_packet_alloc();
    AVFrame *frm = av_frame_alloc();
    AVFrame *rgba = av_frame_alloc();
    rgba->format = AV_PIX_FMT_RGBA; rgba->width = W; rgba->height = H;
    av_frame_get_buffer(rgba, 0);

    int prev_pts = 0;
    while (av_read_frame(fmt, pkt) >= 0) {
        if (pkt->stream_index == vs && avcodec_send_packet(ctx, pkt) == 0) {
            while (avcodec_receive_frame(ctx, frm) == 0) {
                if (!sws) {
                    // yuva420p -> RGBA preserves the alpha plane.
                    sws = sws_getContext(frm->width, frm->height, frm->format,
                                         W, H, AV_PIX_FMT_RGBA, SWS_BILINEAR, NULL, NULL, NULL);
                }
                sws_scale(sws, (const uint8_t * const*)frm->data, frm->linesize, 0,
                          frm->height, rgba->data, rgba->linesize);
                if (!pixels) pixels = malloc(sizeof(int) * frameStride * cap);
                else if (count == cap) {
                    cap *= 2; delays = realloc(delays, sizeof(int) * cap);
                    pixels = realloc(pixels, sizeof(int) * frameStride * cap);
                }
                for (int y = 0; y < H; y++) {
                    memcpy((uint8_t*)(pixels + (size_t)count * frameStride + (size_t)y * W),
                           rgba->data[0] + (size_t)y * rgba->linesize[0], (size_t)W * 4);
                }
                int pts = (int)(av_rescale_q(frm->best_effort_timestamp, st->time_base, (AVRational){1,1000}));
                delays[count] = count == 0 ? 33 : (pts - prev_pts > 0 ? pts - prev_pts : 33);
                prev_pts = pts; count++;
            }
        }
        av_packet_unref(pkt);
    }
    av_frame_free(&rgba); av_frame_free(&frm); av_packet_free(&pkt);
    if (sws) sws_freeContext(sws);
    avcodec_free_context(&ctx); avformat_close_input(&fmt);
    if (count == 0) { free(pixels); free(delays); return NULL; }
    Decoded *d = malloc(sizeof(Decoded));
    d->pixels = pixels; d->delays = delays; d->count = count; d->w = W; d->h = H;
    return d;
}

JNIEXPORT jobject JNICALL
Java_dev_lyo_hortay_webm_WebmAlphaNative_nativeDecode(JNIEnv *env, jclass clazz,
        jstring jpath, jint outW, jint outH) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    Decoded *d = decode_all(path, outW, outH);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (!d) return NULL;
    jintArray pixels = (*env)->NewIntArray(env, (jsize)((size_t)d->count * d->w * d->h));
    (*env)->SetIntArrayRegion(env, pixels, 0, (jsize)((size_t)d->count * d->w * d->h), d->pixels);
    jintArray delays = (*env)->NewIntArray(env, d->count);
    (*env)->SetIntArrayRegion(env, delays, 0, d->count, d->delays);
    jclass holder = (*env)->FindClass(env, "dev/lyo/hortay/webm/WebmAlphaNative$Raw");
    jmethodID ctor = (*env)->GetMethodID(env, holder, "<init>", "([I[III I)V");
    jobject obj = (*env)->NewObject(env, holder, ctor, pixels, delays, d->count, d->w, d->h);
    free_decoded(d);
    return obj;
}
```

> NOTE: `Raw` ctor signature `([I[III I)V` = `(int[] pixels, int[] delays, int count, int width, int height)`. Keep in lockstep with the Kotlin class in Step 3. Android `Bitmap.Config.ARGB_8888` stores ints as little-endian RGBA; ffmpeg `AV_PIX_FMT_RGBA` is byte order R,G,B,A. Verify against Task 1.2; if alpha/red look swapped, switch swscale to `AV_PIX_FMT_BGRA` and document it here.

- [ ] **Step 3: Write `WebmAlphaNative.kt`**

```kotlin
package dev.lyo.hortay.webm

/** JNI surface for the minimal ffmpeg VP9+alpha WebM decoder. Single shot: decode a whole
 *  short loop to RGBA frames. See scripts/build-ffmpeg.sh for the codec build. */
object WebmAlphaNative {
    init { System.loadLibrary("hortaywebm") }

    /** Decodes [path] into RGBA frames scaled to [outW]x[outH] (<=0 keeps intrinsic).
     *  Returns null when the file is missing, not VP9, or carries no frames. */
    @JvmStatic external fun nativeDecode(path: String, outW: Int, outH: Int): Raw?

    /** Flat decode result. pixels = count*width*height ints, ARGB_8888-packed, frame-major. */
    class Raw(
        @JvmField val pixels: IntArray,
        @JvmField val delays: IntArray,
        @JvmField val count: Int,
        @JvmField val width: Int,
        @JvmField val height: Int,
    )
}
```

- [ ] **Step 4: Build the native lib**

Run: `./gradlew :libwebm:assembleDebug -q`
Expected: BUILD SUCCESSFUL; `libhortaywebm.so` for both ABIs under `libwebm/build/intermediates/`.

- [ ] **Step 5: Commit**

```bash
git add libwebm/src/main/cpp/CMakeLists.txt libwebm/src/main/cpp/webm_alpha_decoder.c libwebm/src/main/java/dev/lyo/hortay/webm/WebmAlphaNative.kt
git commit -m "feat(webm): JNI ffmpeg decoder for VP9+alpha WebM -> RGBA frames"
```

### Task 1.2: Instrumented test — decoder preserves alpha

**Files:**
- Create: `app/src/androidTest/assets/round_sticker.webm` (a real Telegram transparent sticker, ≤256 KB)
- Create: `app/src/androidTest/kotlin/dev/lyo/hortay/webm/WebmAlphaNativeTest.kt`

- [ ] **Step 1: Add the test asset** — a known transparent WebM (corners transparent, centre opaque).

- [ ] **Step 2: Write the instrumented test**

```kotlin
package dev.lyo.hortay.webm

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WebmAlphaNativeTest {
    private fun copyAsset(name: String): String {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val out = File(ctx.cacheDir, name)
        ctx.assets.open(name).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        return out.absolutePath
    }

    @Test fun decodesMultipleFramesWithRealAlpha() {
        val raw = WebmAlphaNative.nativeDecode(copyAsset("round_sticker.webm"), 64, 64)
        assertNotNull("decode returned null", raw); raw!!
        assertTrue("expected an animation", raw.count > 1)
        assertEquals(64 * 64 * raw.count, raw.pixels.size)
        val corner = raw.pixels[0]                    // top-left
        val centre = raw.pixels[(32 * 64) + 32]       // middle
        assertEquals("corner should be transparent", 0, (corner ushr 24) and 0xFF)
        assertTrue("centre should be opaque-ish", ((centre ushr 24) and 0xFF) > 200)
    }
}
```

- [ ] **Step 3: Run on a device/emulator**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*WebmAlphaNativeTest*"`
Expected: PASS. If the alpha assertion fails because byte order is reversed, switch swscale to `AV_PIX_FMT_BGRA` in `decode_all` (Task 1.1) and re-run; document the final order in the C file.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/assets/round_sticker.webm app/src/androidTest/kotlin/dev/lyo/hortay/webm/WebmAlphaNativeTest.kt
git commit -m "test(webm): instrumented alpha-preservation test for the native decoder"
```

---

## Phase 2 — Decode result, cache, clock (unit-testable Kotlin)

### Task 2.1: `DecodedWebm` + frame-index helper + Kotlin decoder wrapper

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/media/DecodedWebm.kt`
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/media/WebmAlphaDecoder.kt`
- Create: `app/src/test/kotlin/dev/lyo/hortay/data/media/DecodedWebmTest.kt`

- [ ] **Step 1: Write `DecodedWebm.kt`** (frame-index logic extracted so it's testable without `ImageBitmap`)

```kotlin
package dev.lyo.hortay.data.media

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/** Frame index for a looping playback of [delaysMs] at [elapsedMs]. Pure; unit-tested. */
fun frameIndexFor(delaysMs: IntArray, elapsedMs: Long): Int {
    if (delaysMs.size <= 1) return 0
    val total = delaysMs.sum().coerceAtLeast(1)
    var t = (elapsedMs % total).toInt()
    for (i in delaysMs.indices) { t -= delaysMs[i]; if (t < 0) return i }
    return delaysMs.lastIndex
}

/** A fully-decoded short WebM loop, ready to draw. [frames] and [delaysMs] are 1:1. */
@Immutable
class DecodedWebm(
    val frames: List<ImageBitmap>,
    val delaysMs: IntArray,
    val width: Int,
    val height: Int,
) {
    fun frameAt(elapsedMs: Long): Int =
        frameIndexFor(delaysMs, elapsedMs).coerceIn(0, frames.lastIndex)
}
```

- [ ] **Step 2: Write `WebmAlphaDecoder.kt`**

```kotlin
package dev.lyo.hortay.data.media

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.lyo.hortay.webm.WebmAlphaNative

/** Thin bridge: native flat RGBA -> per-frame [ImageBitmap]. Pure CPU; call off the main thread.
 *  @param sizePx target square render size — frames are scaled at decode time so a 48px emoji
 *  never holds 512px frames in the cache. */
object WebmAlphaDecoder {
    fun decode(path: String, sizePx: Int): DecodedWebm? {
        val raw = WebmAlphaNative.nativeDecode(path, sizePx, sizePx) ?: return null
        val stride = raw.width * raw.height
        val frames = ArrayList<ImageBitmap>(raw.count)
        for (i in 0 until raw.count) {
            val bmp = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(raw.pixels, i * stride, raw.width, 0, 0, raw.width, raw.height)
            frames += bmp.asImageBitmap()
        }
        return DecodedWebm(frames, raw.delays, raw.width, raw.height)
    }
}
```

- [ ] **Step 3: Unit test the helper**

```kotlin
package dev.lyo.hortay.data.media

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class DecodedWebmTest {
    @Test fun loopsAcrossDuration() {
        val d = intArrayOf(100, 100, 100) // 300ms loop
        assertEquals(0, frameIndexFor(d, 0))
        assertEquals(1, frameIndexFor(d, 150))
        assertEquals(2, frameIndexFor(d, 250))
        assertEquals(0, frameIndexFor(d, 300))   // wraps
        assertEquals(1, frameIndexFor(d, 450))
    }
    @Test fun singleFrameAlwaysZero() = assertEquals(0, frameIndexFor(intArrayOf(100), 99999))
    @Test fun emptyIsZero() = assertEquals(0, frameIndexFor(intArrayOf(), 5))
}
```

- [ ] **Step 4: Run**

Run: `./gradlew :app:testDebugUnitTest --tests "*DecodedWebmTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/media/DecodedWebm.kt app/src/main/kotlin/dev/lyo/hortay/data/media/WebmAlphaDecoder.kt app/src/test/kotlin/dev/lyo/hortay/data/media/DecodedWebmTest.kt
git commit -m "feat(webm): DecodedWebm frame model + decoder bridge"
```

### Task 2.2: `WebmFrameCache` — byte-bounded LRU, off-thread decode

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/data/media/WebmFrameCache.kt`
- Create: `app/src/test/kotlin/dev/lyo/hortay/data/media/WebmFrameCacheTest.kt`

- [ ] **Step 1: Write the cache** (decode injected for testability; `sizeOf` takes plain dims so tests need no `ImageBitmap`)

```kotlin
package dev.lyo.hortay.data.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide decoded-loop cache, keyed by (id, sizePx). Decodes once off-thread and shares the
 *  result across every visible instance of the same sticker/emoji — the WebM analogue of how
 *  [dev.lyo.hortay.ui.media.CustomEmojiAnimator] shares one Lottie drawable per id. Byte-bounded LRU
 *  so a long sticker-heavy scroll can't grow unbounded. */
class WebmFrameCache(
    private val scope: CoroutineScope,
    private val maxBytes: Long = 24L * 1024 * 1024,
    private val decode: (path: String, sizePx: Int) -> DecodedWebm? = WebmAlphaDecoder::decode,
) {
    data class Key(val id: String, val sizePx: Int)
    private val flows = HashMap<Key, MutableStateFlow<DecodedWebm?>>()
    private val lru = LinkedHashMap<Key, DecodedWebm>(16, 0.75f, true)
    private val inFlight = HashSet<Key>()
    private val mutex = Mutex()
    private var bytes = 0L

    fun observe(key: Key, path: String): StateFlow<DecodedWebm?> {
        val flow = synchronized(flows) { flows.getOrPut(key) { MutableStateFlow(lru[key]) } }
        if (flow.value == null) ensure(key, path)
        return flow
    }

    private fun ensure(key: Key, path: String) {
        scope.launch(Dispatchers.Default) {
            mutex.withLock { if (key in inFlight || lru[key] != null) return@launch; inFlight += key }
            val decoded = runCatching { decode(path, key.sizePx) }.getOrNull()
            mutex.withLock {
                inFlight -= key
                if (decoded != null) {
                    lru[key] = decoded; bytes += sizeOf(decoded.frames.size, decoded.width, decoded.height)
                    evictDown()
                    synchronized(flows) { flows[key] }?.value = decoded
                }
            }
        }
    }

    private fun sizeOf(frames: Int, w: Int, h: Int): Long = frames.toLong() * w * h * 4
    private fun evictDown() {
        val it = lru.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next(); bytes -= sizeOf(e.value.frames.size, e.value.width, e.value.height); it.remove()
            synchronized(flows) { flows[e.key] }?.value = null
        }
    }
    internal fun currentBytes() = bytes
}
```

- [ ] **Step 2: Unit test eviction** (synchronous fake decode; `DecodedWebm` with empty frame list is fine since `sizeOf` uses dims)

```kotlin
package dev.lyo.hortay.data.media

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class WebmFrameCacheTest {
    // 8 "frames" of 64x64 == 8*64*64*4 bytes each; emptyList() frames keep sizeOf honest via dims.
    private fun fake(px: Int) = DecodedWebm(emptyList(), IntArray(8) { 33 }, px, px).let {
        DecodedWebm(List(8) { _ -> error("not drawn in test") }.let { emptyList() }, IntArray(8) { 33 }, px, px)
    }

    @Test fun evictsOldestOverBudget() = runTest {
        val maxBytes = 64L * 64 * 4 * 10
        val cache = WebmFrameCache(TestScope(testScheduler), maxBytes) { _, px ->
            DecodedWebm(emptyList(), IntArray(8) { 33 }, px, px)
        }
        repeat(3) { i -> cache.observe(WebmFrameCache.Key("s$i", 64), "/p$i.webm") }
        advanceUntilIdle()
        assertTrue(cache.currentBytes() <= maxBytes)
    }
}
```

> NOTE: `frameAt` is never called in this test, so `frames = emptyList()` is safe. If `DecodedWebm` later validates `frames.size == delaysMs.size`, relax that for the cache test or pass matching sizes.

- [ ] **Step 3: Run**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebmFrameCacheTest*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/media/WebmFrameCache.kt app/src/test/kotlin/dev/lyo/hortay/data/media/WebmFrameCacheTest.kt
git commit -m "feat(webm): byte-bounded LRU frame cache with off-thread decode"
```

### Task 2.3: `WebmAnimationClock`

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/media/WebmAnimationClock.kt`

- [ ] **Step 1: Write the clock**

```kotlin
package dev.lyo.hortay.ui.media

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis

/** One time source shared by every on-screen WebM animation. Composables read [nowMs] and compute
 *  their own frame via DecodedWebm.frameAt; a single Choreographer loop drives them all, so N inline
 *  emoji cost N cheap draws off one clock — not N players. */
class WebmAnimationClock {
    private val _nowMs = mutableLongStateOf(0L)
    val nowMs: Long get() = _nowMs.longValue
    /** Mount once high in the tree: `LaunchedEffect(clock){ clock.run() }`. */
    suspend fun run() { while (true) { withFrameMillis { _nowMs.longValue = it } } }
}

val LocalWebmClock = staticCompositionLocalOf { WebmAnimationClock() }
```

> NOTE: mirror exactly how `CustomEmojiAnimator` mounts its frame driver (find its `withFrameMillis`/`LaunchedEffect` site) so both share one Choreographer cadence rather than two.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/media/WebmAnimationClock.kt
git commit -m "feat(webm): shared animation clock for all on-screen WebM instances"
```

---

## Phase 3 — DI + Compose renderer

### Task 3.1: Inject cache + clock; CompositionLocals

**Files:**
- Modify: `app/src/main/kotlin/.../AppGraph.kt`
- Modify: `app/src/main/kotlin/.../MainActivity.kt`
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/media/LocalWebmFrameCache.kt`
- Modify: `config/detekt/baseline.xml`

- [ ] **Step 1:** In `AppGraph`, add `val webmFrameCache = WebmFrameCache(appScope)` reusing the same application `CoroutineScope` that `MediaCache` uses (find and mirror).

- [ ] **Step 2:** Create the local:
```kotlin
package dev.lyo.hortay.ui.media
import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.hortay.data.media.WebmFrameCache
val LocalWebmFrameCache = staticCompositionLocalOf<WebmFrameCache> { error("WebmFrameCache not provided") }
```

- [ ] **Step 3:** In `MainActivity`, beside the `LocalMediaCache`/`LocalExoPlayerPool` providers add `LocalWebmFrameCache provides graph.webmFrameCache` and `LocalWebmClock provides remember { WebmAnimationClock() }`; mount `val clock = LocalWebmClock.current; LaunchedEffect(clock){ clock.run() }` once inside the providers.

- [ ] **Step 4:** Add to `config/detekt/baseline.xml` under `CompositionLocalAllowlist` (alphabetical), matching the `LocalMediaPassive` convention:
```xml
<ID>CompositionLocalAllowlist:LocalWebmFrameCache.kt$LocalWebmFrameCache</ID>
<ID>CompositionLocalAllowlist:WebmAnimationClock.kt$LocalWebmClock</ID>
```

- [ ] **Step 5: Build + commit**

Run: `./gradlew :app:compileDebugKotlin -q` → no errors.
```bash
git add -A && git commit -m "feat(webm): wire WebmFrameCache + clock into AppGraph/MainActivity"
```

### Task 3.2: `WebmAlphaImage` composable

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/media/WebmAlphaImage.kt`

- [ ] **Step 1: Write the renderer**

```kotlin
package dev.lyo.hortay.ui.media

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.media.WebmFrameCache

/** Draws an animated VP9+alpha WebM via the shared decode cache + clock. No SurfaceView, no
 *  ExoPlayer; the current frame is a plain ImageBitmap drawn with native alpha (srcOver).
 *  [animate]=false paints frame 0 (reduced-motion / off-focus). Falls back to nothing when [path]
 *  is null or decode hasn't completed — callers keep a static thumb underneath until [onFirstFrame]. */
@Composable
fun WebmAlphaImage(
    key: String,
    path: String?,
    sizePx: Int,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    onFirstFrame: () -> Unit = {},
) {
    if (path == null || sizePx <= 0) return
    val cache = LocalWebmFrameCache.current
    val clock = LocalWebmClock.current
    val decoded by remember(key, sizePx, path) { cache.observe(WebmFrameCache.Key(key, sizePx), path) }
        .collectAsStateWithLifecycle()

    LaunchedEffect(decoded != null) { if (decoded != null) onFirstFrame() }
    val base = remember(decoded, animate) { clock.nowMs }
    val d = decoded ?: return
    val idx = if (animate) d.frameAt(clock.nowMs - base) else 0
    val frame = d.frames[idx]

    Canvas(modifier) {
        drawIntoCanvas { c ->
            val sx = size.width / frame.width
            val sy = size.height / frame.height
            c.save(); c.scale(sx, sy)
            c.drawImage(frame, Offset.Zero, Paint())
            c.restore()
        }
    }
}
```

> NOTE: reading `clock.nowMs` inside `Canvas`'s draw triggers redraw each frame only for mounted instances — confirm it invalidates draw (not full recomposition). If recomposition is too coarse, move the `nowMs` read into a `drawWithCache`/`graphicsLayer` so only the draw phase re-runs.

- [ ] **Step 2: Build + commit**

Run: `./gradlew :app:compileDebugKotlin -q` → no errors.
```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/media/WebmAlphaImage.kt
git commit -m "feat(webm): WebmAlphaImage Compose renderer (Canvas, native alpha)"
```

---

## Phase 4 — Rewire existing surfaces

### Task 4.1: Replace the ExoPlayer body of `WebmStickerPlayer`

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/ui/media/WebmStickerPlayer.kt`

- [ ] **Step 1:** Keep the signature + `rememberMediaBinding` (file still downloads, `readyPath` resolves). Replace the `AndroidView{TextureView}` + ExoPlayer acquire/loop/listener block with:

```kotlin
val sizePx = with(androidx.compose.ui.platform.LocalDensity.current) { STICKER_MAX_SIDE.roundToPx() }
Box(modifier) {
    if (thumb != null && !firstFrameRendered) {
        TdMediaImage(thumb, contentDescription, Modifier.fillMaxSize(),
            ContentScale.Fit, null, false, priority)
    }
    val path = if (isRemote) /* see NOTE */ remoteLocalPath else binding.readyPath
    WebmAlphaImage(
        key = fileId?.toString() ?: remoteUrl.orEmpty(),
        path = path,
        sizePx = sizePx,
        modifier = Modifier.fillMaxSize(),
        animate = true,
        onFirstFrame = { firstFrameRendered = true },
    )
}
```
Delete: `pool.acquire`, `setVideoTextureView`, `clearVideoTextureView`, `REPEAT_MODE`/`seekTo` loop listener. Rewrite the class KDoc — ExoPlayer no longer used for WebM; loop/alpha rationale now lives in `WebmAlphaImage`/`WebmFrameCache`.

> NOTE (guest mode): the native decoder needs a local file path; guest-mode WebM is a URL. Resolve it to a local file first via the existing web/Coil disk pipeline (the same on-disk path the guest fullscreen viewer uses), then pass that path. If that resolution is non-trivial, scope this task to TDLib-mode only and open a follow-up for guest-mode WebM alpha — document the decision in the KDoc.

- [ ] **Step 2: On-device verification** — open a chat with a round/transparent WebM sticker.
Expected: transparent background (round shape visible), animates, loops cleanly, no black square.

- [ ] **Step 3: Commit** `feat(webm): render WebM stickers with alpha via WebmAlphaImage`

### Task 4.2: Animate WebM custom emoji inline

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/ui/media/CustomEmojiInlineView.kt` (the `StickerFormat.Webm` branch, ~177-233)

- [ ] **Step 1:** Replace the "static thumb unless animateAlways" body with `WebmAlphaImage` using the file's `readyPath` (resolved the same way other branches in this file resolve `media.fileId` → MediaCache), keyed `"emoji_${sticker.id}"`, decoded at the inline px size, gated `animate = canAnimate && !reducedMotion`. Keep the static `TdMediaImage(thumb)` underlay until `onFirstFrame`.

```kotlin
StickerFormat.Webm -> {
    val sizePx = with(LocalDensity.current) { /* inline side dp */ .roundToPx() }
    Box(Modifier.fillMaxSize()) {
        var rendered by remember(sticker.id) { mutableStateOf(false) }
        if (sticker.thumb != null && !rendered) {
            TdMediaImage(sticker.thumb, null, Modifier.fillMaxSize(), ContentScale.Fit, null, false)
        }
        WebmAlphaImage(
            key = "emoji_${sticker.id}",
            path = /* readyPath for sticker.media.fileId via MediaCache, as elsewhere here */ null,
            sizePx = sizePx,
            modifier = Modifier.fillMaxSize(),
            animate = canAnimate && !reducedMotion,
            onFirstFrame = { rendered = true },
        )
    }
}
```

> NOTE: reuse the file's existing `canAnimate` derivation (focus/animateAlways) verbatim — do not invent a new gate. `reducedMotion` = the same `ValueAnimator.getDurationScale() == 0f` check used by `rememberDeferredLoading`'s `effectiveSkeletonGrace`.

- [ ] **Step 2: On-device verification** — a post with WebM custom emoji shows them animating inline, transparent; 20+ on screen stay smooth (one decode per id, shared).

- [ ] **Step 3: Commit** `feat(webm): animate WebM custom emoji inline via shared frame cache`

### Task 4.3: ExoPlayerPool KDoc + dead-path check

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/ui/media/ExoPlayerPool.kt`

- [ ] **Step 1:** Update the KDoc list of muted users (remove "WebM stickers, custom emoji"; ExoPlayer is now MP4/round/fullscreen only). Run `rg "WebmStickerPlayer\(|setVideoTextureView"` to confirm no WebM caller still hits ExoPlayer.
- [ ] **Step 2: Commit** `docs(media): ExoPlayer is MP4-only after WebM moved to ffmpeg`.

---

## Phase 5 — Reuse across reactions & emoji-status (optional, after core ships)

### Task 5.1: Animated message reactions
- [ ] If a reaction renders a WebM/animated document, point its renderer at `WebmAlphaImage` keyed by the reaction document id. Same cache/clock; no new native code. Verify a reacting post shows the animated reaction with alpha.

### Task 5.2: Animated emoji-status
- [ ] Where the emoji-status badge renders (settings hero, user-profile sheet, comments) and the status is `StickerFormat.Webm`, render via `WebmAlphaImage` instead of the static thumb. Verify a WebM status animates next to the name.

> Keep these as separate low-risk follow-ups; ship Phase 0–4 first.

---

## Phase 6 — Perf, docs, gates

### Task 6.1: Density + memory verification
- [ ] Manually (or via macrobench) scroll a sticker-heavy + emoji-heavy feed. Expected: zero decode on the main thread (all `Dispatchers.Default`), jank within budget, `WebmFrameCache.currentBytes() <= maxBytes`. Tune `maxBytes` if eviction thrashes.

### Task 6.2: ARCHITECTURE + CHANGELOG + gates
- [ ] `ARCHITECTURE.md`: add a load-bearing row — "WebM alpha pipeline | `data/media/WebmFrameCache.kt` + `ui/media/WebmAlphaImage.kt` | minimal ffmpeg software decode → shared frame cache → Canvas; ExoPlayer is MP4-only; **never broaden the ffmpeg build**." Add `:libwebm` / `libhortaywebm.so` to Critical identifiers.
- [ ] `CHANGELOG.md` `[Unreleased]` → Added: "Video stickers and custom emoji now play with their real transparency and animate inline, instead of showing as a flat square or a frozen image."
- [ ] `./gradlew :app:detekt` — only the two new CompositionLocals expected (baselined in 3.1).
- [ ] `./gradlew :app:lintRelease` — MissingTranslation gate; mirror any new user-facing string across all locales (the CHANGELOG copy is not a string resource).
- [ ] Commit `docs(webm): architecture + changelog for the alpha sticker/emoji pipeline`.

---

## Self-Review notes (carried into execution)

- **Spec coverage:** alpha stickers (4.1), animated WebM emoji (4.2), reuse across reactions/status (5.x), minimal ffmpeg build only (0.2 flags + 6.2 ARCHITECTURE guardrail), optimized density via shared cache+clock (2.2/2.3/3.2). ✓
- **Type consistency:** `WebmAlphaNative.Raw([I[III I)` ↔ C ctor (1.1) ↔ `WebmAlphaDecoder` (2.1); `WebmFrameCache.Key(id,sizePx)` identical in cache (2.2) and renderer (3.2); `frameIndexFor`/`DecodedWebm.frameAt` defined 2.1, used 3.2. ✓
- **Implementer decisions flagged inline (resolve at the task, document in code):** RGBA vs BGRA byte order (1.1 NOTE + 1.2 Step 3); guest-mode remote→local path (4.1 NOTE); `nowMs` invalidation scope — recomposition vs draw phase (3.2 NOTE); shared ffmpeg `include/` collapse (0.2 NOTE).
- **Highest-risk gates:** ffmpeg `configure` flag set linking cleanly (0.2 Step 3) and native RGBA byte order (1.2 Step 3). Both fail loudly and early.
- **Out of scope (explicitly):** broadening the ffmpeg build to other codecs/audio/transcode; replacing ExoPlayer for hardware-decodable media.
