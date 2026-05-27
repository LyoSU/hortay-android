package dev.lyo.hortay.data.media

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.lyo.hortay.webm.WebmAlphaNative

/** Thin bridge: native flat BGRA -> per-frame [ImageBitmap]. Pure CPU; call off the main thread.
 *  @param sizePx target square render size — frames are scaled at decode time so a 48px emoji
 *  never holds 512px frames in the cache.
 *
 *  Returns null (callers fall back to the static thumb) on ANY failure, including the native
 *  library being absent on an ABI we didn't ship libhortaywebm.so for (e.g. 32-bit devices) —
 *  the [WebmAlphaNative] class-init `loadLibrary` throws there, so we must catch [Throwable],
 *  not just [Exception]. */
object WebmAlphaDecoder {
    fun decode(path: String, sizePx: Int): DecodedWebm? = try {
        val raw = WebmAlphaNative.nativeDecode(path, sizePx, sizePx) ?: return null
        val stride = raw.width * raw.height
        val frames = ArrayList<ImageBitmap>(raw.count)
        for (i in 0 until raw.count) {
            val bmp = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(raw.pixels, i * stride, raw.width, 0, 0, raw.width, raw.height)
            frames += bmp.asImageBitmap()
        }
        DecodedWebm(frames, raw.delays, raw.width, raw.height)
    } catch (_: Throwable) {
        null
    }
}
