package dev.lyo.hortay.data.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import java.io.ByteArrayOutputStream

/**
 * Encodes thumbnails for archive storage.
 *
 * - Single posts: stores the unmodified `Minithumbnail.data` bytes (already JPEG).
 * - Albums: tiles up to three first members horizontally into a single JPEG (q=70)
 *   so the archive row can show "this is what the album looked like" without
 *   keeping all members.
 *
 * Implementation runs on Android — uses `android.graphics.Bitmap`. Not unit-testable
 * on the JVM; rendering is validated visually in the archive list (no separate test
 * file).
 */
object MinithumbCompositor {

    /** Returns the input unchanged. Defined as a method so call sites consistently
     *  use the compositor surface regardless of single-vs-composite. */
    fun single(jpeg: ByteArray?): ByteArray? = jpeg

    /** Up to 3 thumbs tiled horizontally, JPEG-encoded at q=70. Returns null when
     *  every input failed to decode. */
    fun composite(jpegs: List<ByteArray>): ByteArray? {
        if (jpegs.isEmpty()) return null
        if (jpegs.size == 1) return jpegs[0]
        val tiles = jpegs.take(3).mapNotNull { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (tiles.isEmpty()) return null
        val tileH = tiles.maxOf { it.height }
        val tileW = tiles.maxOf { it.width }
        val out = Bitmap.createBitmap(tileW * tiles.size, tileH, Bitmap.Config.RGB_565)
        val canvas = Canvas(out)
        tiles.forEachIndexed { i, b ->
            canvas.drawBitmap(b, null, Rect(i * tileW, 0, (i + 1) * tileW, tileH), null)
        }
        val baos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        return baos.toByteArray()
    }
}
