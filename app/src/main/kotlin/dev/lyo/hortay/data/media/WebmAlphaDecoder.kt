package dev.lyo.hortay.data.media

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.lyo.hortay.webm.WebmAlphaNative

/** Thin bridge: native flat BGRA -> per-frame [ImageBitmap]. Pure CPU; call off the main thread.
 *  @param widthPx target render width
 *  @param heightPx target render height — frames are scaled at decode time to the laid-out box so
 *  a 48px emoji never holds 512px frames and a non-square sticker keeps its aspect ratio (an
 *  earlier square-only decode squished 512x384 stickers).
 *
 *  Returns null (callers fall back to the static thumb) on ANY failure, including the native
 *  library being absent on an ABI we didn't ship libhortaywebm.so for (e.g. 32-bit devices) —
 *  the [WebmAlphaNative] class-init `loadLibrary` throws there, so we must catch [Throwable],
 *  not just [Exception]. */
object WebmAlphaDecoder {
    // catch(Throwable) is deliberate: a missing/incompatible libhortaywebm.so surfaces as
    // UnsatisfiedLinkError (an Error, not an Exception), which a narrower catch would miss.
    @Suppress("TooGenericExceptionCaught")
    fun decode(path: String, widthPx: Int, heightPx: Int): DecodedWebm? = try {
        val raw = WebmAlphaNative.nativeDecode(path, widthPx, heightPx) ?: return null
        val stride = raw.width * raw.height
        val frames = ArrayList<ImageBitmap>(raw.count)
        for (i in 0 until raw.count) {
            val bmp = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(raw.pixels, i * stride, raw.width, 0, 0, raw.width, raw.height)
            frames += bmp.asImageBitmap()
        }
        DecodedWebm(frames, raw.delays, raw.width, raw.height)
    } catch (t: Throwable) {
        // Surfaces a missing/incompatible libhortaywebm.so (UnsatisfiedLinkError) or a native
        // decode crash, which would otherwise silently fall back to the static thumb.
        Log.w("WebmAlphaDecoder", "decode($path, ${widthPx}x$heightPx) failed", t)
        null
    }
}
