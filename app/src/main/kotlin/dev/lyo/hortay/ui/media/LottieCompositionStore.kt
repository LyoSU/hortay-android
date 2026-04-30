package dev.lyo.hortay.ui.media

import android.util.Log
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream

/**
 * Process-wide LRU cache of parsed [LottieComposition]s keyed by absolute file path.
 *
 * Why a manual LRU instead of leaning on Lottie's `LottieCompositionFactory.fromUrl`
 * cache: TGS files come off TDLib's filesystem, not URLs, so the built-in cache (which
 * keys on URLs and weakly references compositions) doesn't help. Parsing is the
 * expensive bit — the JSON is megabytes-shaped and parses into a deep object graph —
 * so reusing across composables matters more than the bytes.
 *
 * Eviction policy: bounded by [MAX_ENTRIES]. Each composition is on the order of
 * 100 KB-1 MB of resident memory, so 32 entries (~5-30 MB worst-case) is the sweet spot
 * for a feed where the user might scroll past 100s of stickers but only see a few at a
 * time. Evicting on scroll is fine — Lottie reparses in well under a frame, and the
 * sticker thumbnail bridges the gap visually anyway.
 *
 * Security:
 *   • TGS files are gzipped Lottie JSON. We hard-cap the decompressed payload at
 *     [MAX_DECOMPRESSED_BYTES] to defuse zip-bomb shaped attacks (a 5 MB gzip can blow
 *     up to gigabytes of JSON; without the cap one malicious sticker would OOM the
 *     process). 5 MB is generous — typical Telegram TGS files are 30-300 KB
 *     decompressed; even ornate ones rarely cross 1 MB. We log and skip past the cap
 *     instead of throwing so one bad file can't take down the surrounding feed.
 *   • Parse failures (truncated downloads, corrupted gzip) are caught and surfaced as
 *     null — the renderer falls back to the static thumbnail.
 */
internal object LottieCompositionStore {

    private val lru = object : LinkedHashMap<String, LottieComposition>(MAX_ENTRIES, 0.75f, /* accessOrder */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LottieComposition>?): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * Returns the parsed composition for [path] (a .tgs file on disk), parsing it on
     * first access. Subsequent calls hit the LRU. The decompression and parse run on
     * [Dispatchers.IO] — never block the Compose thread.
     */
    suspend fun load(path: String): LottieComposition? {
        synchronized(lru) { lru[path]?.let { return it } }
        return withContext(Dispatchers.IO) {
            val json = decompressTgsSafely(path) ?: return@withContext null
            // fromJsonStringSync runs on the calling thread (we're already on IO). The
            // cacheKey isn't used — passing null avoids contributing to Lottie's own
            // global cache, which would otherwise hold strong refs in parallel to ours.
            val result = LottieCompositionFactory.fromJsonStringSync(json, /* cacheKey */ null)
            val composition = result.value ?: run {
                Log.w(TAG, "lottie parse failed for $path: ${result.exception?.message}")
                return@withContext null
            }
            synchronized(lru) { lru[path] = composition }
            composition
        }
    }

    private fun decompressTgsSafely(path: String): String? = try {
        val out = ByteArrayOutputStream(64 * 1024)
        FileInputStream(path).use { fis ->
            GZIPInputStream(fis, 16 * 1024).use { gis ->
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = gis.read(buf)
                    if (read < 0) break
                    total += read
                    if (total > MAX_DECOMPRESSED_BYTES) {
                        Log.w(TAG, "tgs payload exceeds cap ($total > $MAX_DECOMPRESSED_BYTES) for $path; skipping")
                        return null
                    }
                    out.write(buf, 0, read)
                }
            }
        }
        // TGS payloads are UTF-8 JSON by spec.
        out.toString(Charsets.UTF_8.name())
    } catch (t: Throwable) {
        if (t is kotlin.coroutines.cancellation.CancellationException) throw t
        Log.w(TAG, "tgs decompress failed for $path", t)
        null
    }

    private const val TAG = "LottieCompositionStore"
    private const val MAX_ENTRIES = 32
    private const val MAX_DECOMPRESSED_BYTES = 5L * 1024L * 1024L
}
