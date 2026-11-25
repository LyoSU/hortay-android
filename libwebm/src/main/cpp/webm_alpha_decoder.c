// Minimal WebM (VP9 + alpha) decoder: decodes a short sticker loop entirely into BGRA
// frames scaled to a target size, returned to Kotlin as a flat ARGB_8888-packed int[].
// Android/MediaCodec can't decode the VP9 alpha plane (androidx/media#1388). Telegram stores
// the alpha as a separate VP9 stream in the WebM BlockAdditional; only the *libvpx* decoder
// reconstructs it as yuva420p (the native ffmpeg vp9 decoder drops it). swscale yuva420p->BGRA
// preserves the alpha plane. See scripts/build-ffmpeg.sh (--enable-libvpx).
//
// Pixel order: AV_PIX_FMT_BGRA is memory bytes B,G,R,A. Android Bitmap.Config.ARGB_8888 +
// Bitmap.setPixels expect each int as 0xAARRGGBB; on little-endian ARM the BGRA byte run loads
// as exactly that int, so colours land right. (RGBA here swaps red<->blue — the "broken
// colours" symptom observed on-device.)
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>

// Hard ceiling on decoded frames. Telegram video stickers/emoji are short loops (tens of frames);
// this only fires if a non-sticker WebM ever reaches this path, bounding the all-into-RAM decode
// (count*W*H*4 bytes) so a pathological input can't OOM the process. We stop cleanly and return
// what we have.
#define MAX_FRAMES 512

// Target number of frames to KEEP. Cached frames are held as RGBA bitmaps (count*W*H*4 bytes), so
// a long loop dominates memory: a 219-frame 320x320 sticker is ~90 MB — enough to evict everything
// else from the shared cache and thrash. We decode every frame (libvpx needs the full stream for
// the alpha plane) but keep only ~this many, evenly spaced, folding dropped frames' durations into
// the kept ones so the loop still plays full-length at a lower fps. Short stickers (<= this) are
// untouched.
#define TARGET_FRAMES 80

// Hard ceiling on the kept frames' total size (count*W*H*4 bytes). Sized to the normal worst case
// on the Kotlin side — TARGET_FRAMES at the 320 px decode cap (MAX_DECODE_SIDE in WebmAlphaImage.kt)
// is ~31 MiB — and a third of WebmFrameCache's 96 MB budget. The same bytes exist up to three times
// at the peak (this native buffer, the Java int[] copy, the per-frame Bitmaps), so an uncapped
// intrinsic-size decode of one sticker could otherwise spike ~200 MB native + ~200 MB Java. The
// keep-stride is derived from this cap too, so a large box keeps fewer, evenly spaced frames over
// the whole loop instead of truncating it; MAX_DECODE_BYTES then stops decoding as a backstop.
#define MAX_DECODE_BYTES (32LL * 1024 * 1024)

// Largest accepted output side. Anything above is not a sticker; also keeps W*H*4 far from int
// overflow in every size computation below.
#define MAX_SIDE 4096

// Upper bound for the source frame-count estimate, applied before any arithmetic on it so a
// hostile nb_frames / duration / frame rate can't overflow the keep-stride computation.
#define MAX_FRAME_ESTIMATE (1LL << 20)

// Bounds for a single frame's delay (ms). Out-of-range or non-monotonic timestamps fall back to
// DEFAULT_DELAY_MS.
#define DEFAULT_DELAY_MS 33
#define MAX_DELAY_MS 10000

typedef struct { int *pixels; int *delays; int count; int w; int h; } Decoded;

static void free_decoded(Decoded *d) {
    if (!d) return;
    free(d->pixels);
    free(d->delays);
    free(d);
}

// Source frame-count estimate, clamped to [0, MAX_FRAME_ESTIMATE]. nb_frames is often unset for
// matroska/VP9, so fall back to duration * average frame rate. 0 means unknown.
static int64_t estimate_frames(const AVFormatContext *fmt, const AVStream *st) {
    int64_t est = st->nb_frames;
    if (est > MAX_FRAME_ESTIMATE) return MAX_FRAME_ESTIMATE;
    if (est > 0) return est;
    double dur_s = 0.0;
    if (st->duration != AV_NOPTS_VALUE && st->duration > 0 && st->time_base.den)
        dur_s = (double)st->duration * av_q2d(st->time_base);
    else if (fmt->duration != AV_NOPTS_VALUE && fmt->duration > 0)
        dur_s = (double)fmt->duration / AV_TIME_BASE;
    double fps = av_q2d(st->avg_frame_rate);
    // Comparison form also rejects NaN; the double product is checked before the int64 cast so the
    // cast can't be undefined behaviour.
    if (!(dur_s > 0.0) || !(fps > 0.0)) return 0;
    double frames = dur_s * fps + 0.5;
    if (!(frames < (double)MAX_FRAME_ESTIMATE)) return MAX_FRAME_ESTIMATE;
    return (int64_t)frames;
}

// Returns malloc'd Decoded* or NULL. outW/outH<=0 means intrinsic size.
static Decoded *decode_all(const char *path, int outW, int outH) {
    AVFormatContext *fmt = NULL;
    if (avformat_open_input(&fmt, path, NULL, NULL) < 0) return NULL;
    if (avformat_find_stream_info(fmt, NULL) < 0) { avformat_close_input(&fmt); return NULL; }
    int vs = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (vs < 0) { avformat_close_input(&fmt); return NULL; }
    AVStream *st = fmt->streams[vs];
    // Telegram stores the alpha as a second VP9 stream in the WebM BlockAdditional. Only the
    // libvpx decoder reconstructs it (yuva420p, 4 planes); ffmpeg's *native* vp9 decoder ignores
    // the alpha block and yields opaque yuv420p (the "still a square" symptom). Prefer libvpx-vp9,
    // fall back to whatever decoder is available for the codec id.
    const AVCodec *codec = avcodec_find_decoder_by_name("libvpx-vp9");
    if (!codec) codec = avcodec_find_decoder(st->codecpar->codec_id);
    if (!codec) { avformat_close_input(&fmt); return NULL; }
    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    if (!ctx) { avformat_close_input(&fmt); return NULL; }
    if (avcodec_parameters_to_context(ctx, st->codecpar) < 0 || avcodec_open2(ctx, codec, NULL) < 0) {
        avcodec_free_context(&ctx);
        avformat_close_input(&fmt);
        return NULL;
    }

    int W = outW > 0 ? outW : ctx->width;
    int H = outH > 0 ? outH : ctx->height;
    if (W <= 0 || H <= 0 || W > MAX_SIDE || H > MAX_SIDE) {
        avcodec_free_context(&ctx);
        avformat_close_input(&fmt);
        return NULL;
    }
    size_t frameStride = (size_t)W * H;
    int64_t frameBytes = (int64_t)frameStride * 4;
    // Frames that fit MAX_DECODE_BYTES, further bounded by MAX_FRAMES. A single frame over the
    // budget means nothing can be returned at all.
    int64_t budgetFrames = MAX_DECODE_BYTES / frameBytes;
    if (budgetFrames > MAX_FRAMES) budgetFrames = MAX_FRAMES;
    if (budgetFrames < 1) {
        avcodec_free_context(&ctx);
        avformat_close_input(&fmt);
        return NULL;
    }
    int maxKeep = (int)budgetFrames;

    // Keep-stride capping cached frames at ~min(TARGET_FRAMES, maxKeep), evenly spaced over the
    // whole loop. Unknown estimate -> stride 1 (keep all; maxKeep still bounds it).
    int64_t est = estimate_frames(fmt, st);
    int64_t targetKeep = maxKeep < TARGET_FRAMES ? maxKeep : TARGET_FRAMES;
    int keepStride = 1;
    if (est > targetKeep) keepStride = (int)((est + targetKeep - 1) / targetKeep);
    if (keepStride < 1) keepStride = 1;

    int64_t rawIdx = 0; // counts every decoded frame; we keep 1 in every keepStride
    struct SwsContext *sws = NULL; // re-validated per frame via sws_getCachedContext
    int cap = 16, count = 0;
    if (cap > maxKeep) cap = maxKeep;
    int *pixels = NULL;
    int *delays = malloc(sizeof(int) * cap);

    AVPacket *pkt = av_packet_alloc();
    AVFrame *frm = av_frame_alloc();
    AVFrame *rgba = av_frame_alloc();
    // Bail out (freeing whatever was allocated) if any allocation failed, rather than
    // dereferencing NULL below.
    if (!delays || !pkt || !frm || !rgba) goto done;
    rgba->format = AV_PIX_FMT_BGRA;
    rgba->width = W;
    rgba->height = H;
    if (av_frame_get_buffer(rgba, 0) < 0) goto done;

    int64_t prev_pts = 0;
    int full = 0; // set once maxKeep frames are kept; stops both loops
    while (!full && av_read_frame(fmt, pkt) >= 0) {
        if (pkt->stream_index == vs && avcodec_send_packet(ctx, pkt) == 0) {
            while (avcodec_receive_frame(ctx, frm) == 0) {
                // Re-checked per frame: one packet can yield several frames.
                if (count >= maxKeep) { full = 1; break; }
                // Decimate: decode every frame (libvpx needs the full stream) but keep only every
                // keepStride-th. Dropped frames' durations fold into the next kept frame because
                // prev_pts only advances on kept frames, so the loop keeps its full wall-clock
                // length at a lower fps.
                if ((rawIdx++ % keepStride) != 0) continue;
                // VP9 may change resolution mid-stream and a frame may arrive without the alpha
                // plane (a different pix_fmt), so the scaler is matched to THIS frame's geometry
                // and format. sws_getCachedContext returns the same context when nothing changed,
                // and on failure has already freed the old one and returns NULL.
                if (frm->width <= 0 || frm->height <= 0 || frm->format < 0 || !frm->data[0]) continue;
                sws = sws_getCachedContext(sws, frm->width, frm->height, frm->format,
                                           W, H, AV_PIX_FMT_BGRA, SWS_BILINEAR, NULL, NULL, NULL);
                if (!sws) continue;
                // yuva420p -> BGRA preserves the alpha plane.
                if (sws_scale(sws, (const uint8_t * const *)frm->data, frm->linesize, 0,
                              frm->height, rgba->data, rgba->linesize) != H) continue;
                if (!pixels) {
                    pixels = malloc(sizeof(int) * frameStride * cap);
                    if (!pixels) { full = 1; break; }
                } else if (count == cap) {
                    // realloc into temps and assign back only on success, so a failure leaves the
                    // already-decoded `count` frames intact in the original buffers (realloc keeps
                    // the old block on NULL return). Then stop with the partial loop.
                    int newCap = cap * 2;
                    if (newCap > maxKeep) newCap = maxKeep;
                    int *np = realloc(pixels, sizeof(int) * frameStride * newCap);
                    if (!np) { full = 1; break; }
                    pixels = np;
                    int *nd = realloc(delays, sizeof(int) * newCap);
                    if (!nd) { full = 1; break; }
                    delays = nd;
                    cap = newCap;
                }
                for (int y = 0; y < H; y++) {
                    memcpy((uint8_t *)(pixels + (size_t)count * frameStride + (size_t)y * W),
                           rgba->data[0] + (size_t)y * rgba->linesize[0], (size_t)W * 4);
                }
                int64_t pts = av_rescale_q(frm->best_effort_timestamp, st->time_base,
                                           (AVRational){1, 1000});
                int64_t delta = pts - prev_pts;
                delays[count] = (count == 0 || delta <= 0 || delta > MAX_DELAY_MS)
                        ? DEFAULT_DELAY_MS : (int)delta;
                prev_pts = pts;
                count++;
            }
        }
        av_packet_unref(pkt);
    }

done:
    av_frame_free(&rgba);
    av_frame_free(&frm);
    av_packet_free(&pkt);
    sws_freeContext(sws);
    avcodec_free_context(&ctx);
    avformat_close_input(&fmt);

    if (count == 0) { free(pixels); free(delays); return NULL; }
    Decoded *d = malloc(sizeof(Decoded));
    if (!d) { free(pixels); free(delays); return NULL; }
    d->pixels = pixels;
    d->delays = delays;
    d->count = count;
    d->w = W;
    d->h = H;
    return d;
}

// Clears a pending Java exception (typically OutOfMemoryError from an array allocation) and reports
// whether there was one. The Kotlin caller already treats a null result as "no frames", so a failed
// allocation degrades to the static thumb instead of aborting the process on the next JNI call.
static int clear_pending(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 1;
    }
    return 0;
}

JNIEXPORT jobject JNICALL
Java_dev_lyo_hortay_webm_WebmAlphaNative_nativeDecode(JNIEnv *env, jclass clazz,
        jstring jpath, jint outW, jint outH) {
    (void)clazz;
    if (!jpath) return NULL;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) { clear_pending(env); return NULL; }
    Decoded *d = decode_all(path, outW, outH);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (!d) return NULL;

    // Bounded by MAX_DECODE_BYTES / 4, so it fits a jsize.
    jsize total = (jsize)((size_t)d->count * d->w * d->h);
    jintArray pixels = (*env)->NewIntArray(env, total);
    if (!pixels || clear_pending(env)) { free_decoded(d); return NULL; }
    (*env)->SetIntArrayRegion(env, pixels, 0, total, d->pixels);
    jintArray delays = (*env)->NewIntArray(env, d->count);
    if (!delays || clear_pending(env)) { free_decoded(d); return NULL; }
    (*env)->SetIntArrayRegion(env, delays, 0, d->count, d->delays);

    jclass holder = (*env)->FindClass(env, "dev/lyo/hortay/webm/WebmAlphaNative$Raw");
    // JNI descriptor for Raw(int[] pixels, int[] delays, int count, int width, int height).
    // NOTE: no spaces allowed in JNI signatures — 2× int[] (`[I`) + 3× int (`I`).
    jmethodID ctor = holder ? (*env)->GetMethodID(env, holder, "<init>", "([I[IIII)V") : NULL;
    // Degrade gracefully on any JNI lookup miss: clear the pending exception and return null
    // (callers fall back to the static thumb) rather than letting NewObject abort the process.
    if (!holder || !ctor) {
        clear_pending(env);
        free_decoded(d);
        return NULL;
    }
    jobject obj = (*env)->NewObject(env, holder, ctor, pixels, delays, d->count, d->w, d->h);
    free_decoded(d);
    if (!obj || clear_pending(env)) return NULL;
    return obj;
}
