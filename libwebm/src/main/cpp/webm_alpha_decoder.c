// Minimal WebM (VP9 yuva420p) decoder: decodes a short sticker loop entirely into RGBA
// frames scaled to a target size, returned to Kotlin as a flat ARGB_8888-packed int[].
// Android/MediaCodec can't decode the VP9 alpha plane (androidx/media#1388); ffmpeg's
// software VP9 decoder + swscale to RGBA preserves it. See scripts/build-ffmpeg.sh.
//
// Pixel order: ffmpeg AV_PIX_FMT_RGBA is byte order R,G,B,A. Android Bitmap.Config.ARGB_8888
// stores each pixel as a little-endian int whose bytes are R,G,B,A — so the packed RGBA bytes
// load directly via Bitmap.setPixels with no swizzle. (Verified by the instrumented test that
// asserts transparent corners; if a device shows red/alpha swapped, switch to AV_PIX_FMT_BGRA.)
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>

typedef struct { int *pixels; int *delays; int count; int w; int h; } Decoded;

static void free_decoded(Decoded *d) {
    if (!d) return;
    free(d->pixels);
    free(d->delays);
    free(d);
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
    if (!codec) { avformat_close_input(&fmt); return NULL; }
    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    avcodec_parameters_to_context(ctx, st->codecpar);
    if (avcodec_open2(ctx, codec, NULL) < 0) { avcodec_free_context(&ctx); avformat_close_input(&fmt); return NULL; }

    int W = outW > 0 ? outW : ctx->width;
    int H = outH > 0 ? outH : ctx->height;
    struct SwsContext *sws = NULL; // created once the source pix_fmt is known
    int cap = 16, count = 0;
    int *pixels = NULL;
    int *delays = malloc(sizeof(int) * cap);
    size_t frameStride = (size_t)W * H;

    AVPacket *pkt = av_packet_alloc();
    AVFrame *frm = av_frame_alloc();
    AVFrame *rgba = av_frame_alloc();
    rgba->format = AV_PIX_FMT_RGBA;
    rgba->width = W;
    rgba->height = H;
    av_frame_get_buffer(rgba, 0);

    int prev_pts = 0;
    while (av_read_frame(fmt, pkt) >= 0) {
        if (pkt->stream_index == vs && avcodec_send_packet(ctx, pkt) == 0) {
            while (avcodec_receive_frame(ctx, frm) == 0) {
                if (!sws) {
                    // yuva420p -> RGBA preserves the alpha plane.
                    sws = sws_getContext(frm->width, frm->height, frm->format,
                                         W, H, AV_PIX_FMT_RGBA, SWS_BILINEAR, NULL, NULL, NULL);
                    if (!sws) { av_packet_unref(pkt); goto done; }
                }
                sws_scale(sws, (const uint8_t * const *)frm->data, frm->linesize, 0,
                          frm->height, rgba->data, rgba->linesize);
                if (!pixels) {
                    pixels = malloc(sizeof(int) * frameStride * cap);
                } else if (count == cap) {
                    cap *= 2;
                    delays = realloc(delays, sizeof(int) * cap);
                    pixels = realloc(pixels, sizeof(int) * frameStride * cap);
                }
                for (int y = 0; y < H; y++) {
                    memcpy((uint8_t *)(pixels + (size_t)count * frameStride + (size_t)y * W),
                           rgba->data[0] + (size_t)y * rgba->linesize[0], (size_t)W * 4);
                }
                int pts = (int)(av_rescale_q(frm->best_effort_timestamp, st->time_base,
                                             (AVRational){1, 1000}));
                delays[count] = count == 0 ? 33 : (pts - prev_pts > 0 ? pts - prev_pts : 33);
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
    if (sws) sws_freeContext(sws);
    avcodec_free_context(&ctx);
    avformat_close_input(&fmt);

    if (count == 0) { free(pixels); free(delays); return NULL; }
    Decoded *d = malloc(sizeof(Decoded));
    d->pixels = pixels;
    d->delays = delays;
    d->count = count;
    d->w = W;
    d->h = H;
    return d;
}

JNIEXPORT jobject JNICALL
Java_dev_lyo_hortay_webm_WebmAlphaNative_nativeDecode(JNIEnv *env, jclass clazz,
        jstring jpath, jint outW, jint outH) {
    (void)clazz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    Decoded *d = decode_all(path, outW, outH);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (!d) return NULL;

    jsize total = (jsize)((size_t)d->count * d->w * d->h);
    jintArray pixels = (*env)->NewIntArray(env, total);
    (*env)->SetIntArrayRegion(env, pixels, 0, total, d->pixels);
    jintArray delays = (*env)->NewIntArray(env, d->count);
    (*env)->SetIntArrayRegion(env, delays, 0, d->count, d->delays);

    jclass holder = (*env)->FindClass(env, "dev/lyo/hortay/webm/WebmAlphaNative$Raw");
    jmethodID ctor = (*env)->GetMethodID(env, holder, "<init>", "([I[III I)V");
    jobject obj = (*env)->NewObject(env, holder, ctor, pixels, delays, d->count, d->w, d->h);
    free_decoded(d);
    return obj;
}
