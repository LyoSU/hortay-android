package dev.lyo.hortay.ui.media

import android.util.Log
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser
import dev.lyo.hortay.data.TdSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Process-wide cache of parsed sticker outline silhouettes, keyed by sticker fileId.
 *
 * TDLib exposes [TdApi.GetStickerOutlineSvgPath] as an OFFLINE method — the daemon
 * holds the vector outline locally (it ships with sticker-set metadata) and returns
 * synchronously over JNI. We use it as the first frame in the
 *   outline → thumb → sticker
 * visual ladder: a tinted silhouette paints instantly while the [TdApi.Thumbnail.file]
 * downloads (~50-500 ms) and then the full .tgs / .webm / .webp lands. Compared to a
 * generic rounded-square placeholder, the silhouette matches the sticker's actual shape
 * and reads as "this specific sticker is about to load", not "an empty box".
 *
 * Caches:
 *  - [lru] — parsed Compose [Path] objects keyed by fileId. Bounded at [MAX_ENTRIES];
 *    each Path is a handful of vector commands so resident memory is trivial (KB-scale
 *    even at the cap). Access-order so cold scrolling self-evicts.
 *  - [negative] — fileIds for which TDLib returned an empty SVG string or an error
 *    (the "outline isn't known" path in the GetStickerOutlineSvgPath docs). Without
 *    this, every recomposition would re-issue the JNI call for the same dead fileId;
 *    on a 30-card viewport during a fast scroll that would dominate the JNI queue.
 *    The result is deterministic per fileId — a missing outline today won't appear
 *    tomorrow within the same process — so the negative cache is fire-and-forget for
 *    the process lifetime. Cleared on logout via [clear] wired through the AppGraph
 *    `TdClient.loggedOut` fan-out so a fresh account doesn't see another account's
 *    "no outline" verdicts.
 *
 * Why a class rather than an `object` (cf. [LottieCompositionStore]): outline resolution
 * needs [TdSender]. Holding it inside a class lets us inject via composition local
 * (`LocalStickerOutline`) and keep the per-call surface a clean `load(fileId)` — UI
 * call sites don't need to know about TdSender plumbing.
 */
class StickerOutlineStore(private val td: TdSender) {

    private val lru = object : LinkedHashMap<Int, Path>(MAX_ENTRIES, 0.75f, /* accessOrder */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Path>?): Boolean =
            size > MAX_ENTRIES
    }
    private val negative: MutableSet<Int> = Collections.synchronizedSet(HashSet())

    /**
     * Returns the parsed silhouette [Path] for [fileId], or null when TDLib has no
     * outline for it (404 / empty SVG / parse failure). The path is in the sticker's
     * native pixel coordinate space (per [TdApi.Sticker.width] / [TdApi.Sticker.height])
     * — callers re-scale to their actual box size at draw time.
     */
    suspend fun load(fileId: Int): Path? {
        if (fileId == 0) return null
        if (negative.contains(fileId)) return null
        synchronized(lru) { lru[fileId]?.let { return it } }

        val svg: String = try {
            td.send(TdApi.GetStickerOutlineSvgPath(fileId, /* forAnimatedEmoji */ false, /* forClickedAnimatedEmojiMessage */ false))
                .text
                .orEmpty()
        } catch (t: Throwable) {
            if (t is kotlin.coroutines.cancellation.CancellationException) throw t
            // TDLib returns code 404 with the documented "outline isn't known" payload
            // and may also reject the call during a logout race. Either way, negative-
            // cache so recomposition doesn't keep re-asking — clearing on logout via
            // [clear] resets the verdict for a fresh account.
            negative.add(fileId)
            return null
        }
        if (svg.isEmpty()) {
            negative.add(fileId)
            return null
        }

        // PathParser.parsePathString runs a recursive-descent SVG-path parser; it
        // allocates a few small intermediate arrays per call. We hand it off to
        // Dispatchers.Default so a pathological 50-command path doesn't pause the
        // Compose dispatcher mid-scroll. Typical TDLib outlines are 5-20 commands so
        // this is well under a frame even when it does run inline.
        return withContext(Dispatchers.Default) {
            val parsed = runCatching {
                val parser = PathParser()
                parser.parsePathString(svg)
                parser.toPath()
            }.getOrElse {
                if (it is kotlin.coroutines.cancellation.CancellationException) throw it
                Log.w(TAG, "outline parse failed for $fileId: ${it.message}")
                negative.add(fileId)
                return@withContext null
            }
            synchronized(lru) { lru[fileId] = parsed }
            parsed
        }
    }

    /**
     * Drop all cached paths and negative verdicts. Called from the TDLib logout
     * fan-out — a new account installs its own sticker sets and any outline cached
     * under the previous account's fileIds is potentially stale.
     */
    fun clear() {
        synchronized(lru) { lru.clear() }
        negative.clear()
    }

    private companion object {
        const val TAG = "StickerOutlineStore"
        // Capacity sized for a fast scroll through a sticker-heavy channel: a viewport
        // holds ~5 stickers, the user can scroll past ~10 viewports/sec, and the cache
        // should comfortably absorb a few minutes of continuous scroll before evicting.
        const val MAX_ENTRIES = 512
    }
}
