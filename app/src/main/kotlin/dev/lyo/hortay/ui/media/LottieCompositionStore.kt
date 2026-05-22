package dev.lyo.hortay.ui.media

import android.util.Log
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import kotlinx.coroutines.CompletableDeferred
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
 * # Negative cache + in-flight dedup
 *
 * Two production bugs the first version of this class shipped with, found via ADB
 * thread profiling under jank:
 *
 *   1. **Parse failures weren't memoised.** Telegram introduced TGS shapes (proprietary
 *      extensions like `"ty":"sh"` variants newer than the Lottie spec) that Lottie's
 *      parser rejects with "Unknown point starts with END_ARRAY". Every time a renderer
 *      composable mounted, `LaunchedEffect(readyPath)` re-ran `load(path)` → gunzip 30-
 *      300 KB → JSON parse → fail. With 30 inline emojis using the same unsupported
 *      sticker, scrolling generated 30+ parse attempts per recomposition cycle —
 *      measured at 11 concurrent parses of the SAME path on a Galaxy S25. 200-500 KB of
 *      transient garbage per failed parse × tens-per-second = the 20 MB/sec allocation
 *      pressure that drove the original jank.
 *   2. **Concurrent callers were not deduplicated.** Even on successful files, 30
 *      simultaneously-mounting consumers of the same id each dispatched their own
 *      `withContext(Dispatchers.IO)` task. Dispatchers.IO defaults to 64 threads, so
 *      Lottie's parser ran 30× in parallel — pointless work; only one parse needs to
 *      succeed for the LRU to feed everyone.
 *
 * Fixed via:
 *   • [failedPaths] — `HashSet<String>` of paths that returned null. Subsequent loads
 *     for those paths short-circuit instantly. Bounded by [MAX_FAILED] LRU eviction
 *     (paths Telegram retired stop hammering the set forever).
 *   • [inFlight] — `HashMap<String, CompletableDeferred<LottieComposition?>>`. First
 *     caller for a path enrols itself + creates the deferred; subsequent concurrent
 *     callers find the deferred and `await` it. Single parse, N awaiters.
 *
 * # Security
 *
 *   • TGS files are gzipped Lottie JSON. We hard-cap the decompressed payload at
 *     [MAX_DECOMPRESSED_BYTES] to defuse zip-bomb shaped attacks (a 5 MB gzip can blow
 *     up to gigabytes of JSON; without the cap one malicious sticker would OOM the
 *     process). 5 MB is generous — typical Telegram TGS files are 30-300 KB
 *     decompressed; even ornate ones rarely cross 1 MB. We log and skip past the cap
 *     instead of throwing so one bad file can't take down the surrounding feed.
 *   • Parse failures (truncated downloads, corrupted gzip, unsupported TGS shapes) are
 *     caught and surfaced as null — the renderer falls back to the static thumbnail.
 */
internal object LottieCompositionStore {

    private val lru = object : LinkedHashMap<String, LottieComposition>(MAX_ENTRIES, 0.75f, /* accessOrder */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LottieComposition>?): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * Bounded negative cache. Access-ordered so paths the user is actively bumping
     * into stay near the top and stale entries fall off when the user moves on.
     */
    private val failedPaths = object : LinkedHashMap<String, Unit>(MAX_FAILED, 0.75f, /* accessOrder */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > MAX_FAILED
    }

    /** In-flight parses, keyed by path. Synchronised access; deferred is released on completion. */
    private val inFlight = HashMap<String, CompletableDeferred<LottieComposition?>>()

    /**
     * Returns the parsed composition for [path] (a .tgs file on disk), parsing it on
     * first access. Subsequent calls hit the LRU. The decompression and parse run on
     * [Dispatchers.IO] — never block the Compose thread. Concurrent callers for the
     * same path share one parse via [inFlight].
     */
    suspend fun load(path: String): LottieComposition? {
        // Hot path: LRU + negative cache check under one lock.
        val cached: LottieComposition? = synchronized(lru) {
            lru[path]?.let { return it }
            if (failedPaths.containsKey(path)) {
                // Touch the access order so still-relevant failures stay near the top
                // of the LRU and don't drop out of the negative cache while the user
                // still has the emoji on screen.
                failedPaths[path] = Unit
                return null
            }
            null
        }
        if (cached != null) return cached

        // Get or create the in-flight deferred — first caller does the work, others
        // await the same result.
        val (deferred, weAreTheOwner) = synchronized(this) {
            val existing = inFlight[path]
            if (existing != null) existing to false
            else {
                val fresh = CompletableDeferred<LottieComposition?>()
                inFlight[path] = fresh
                fresh to true
            }
        }
        if (!weAreTheOwner) return deferred.await()

        // We're responsible for the parse. Run on IO; publish + complete on the way out.
        val composition = try {
            withContext(Dispatchers.IO) {
                val json = decompressTgsSafely(path) ?: return@withContext null
                // fromJsonStringSync runs on the calling thread (we're already on IO).
                // The cacheKey isn't used — passing null avoids contributing to Lottie's
                // own global cache, which would otherwise hold strong refs in parallel
                // to ours.
                val result = LottieCompositionFactory.fromJsonStringSync(json, /* cacheKey */ null)
                val parsed = result.value ?: run {
                    Log.w(TAG, "lottie parse failed for $path: ${result.exception?.message}")
                    return@withContext null
                }
                if (!isCompositionRenderable(parsed, TAG, path)) return@withContext null
                parsed
            }
        } catch (t: Throwable) {
            if (t is kotlin.coroutines.cancellation.CancellationException) {
                // Don't poison the deferred for cancelled callers — somebody else might
                // still want the result. Complete with null so concurrent awaiters
                // unblock; the negative cache decision below is based on a deterministic
                // null, not cancellation.
                synchronized(this) { inFlight.remove(path) }
                deferred.complete(null)
                throw t
            }
            Log.w(TAG, "lottie load threw for $path", t)
            null
        }

        // Publish + complete under the same lock as inFlight removal so a second wave
        // of callers that arrives just after this point lands on the LRU (positive) or
        // the negative cache (deterministic failure) rather than re-enrolling a fresh
        // deferred.
        synchronized(this) {
            if (composition != null) {
                synchronized(lru) { lru[path] = composition }
            } else {
                synchronized(lru) { failedPaths[path] = Unit }
            }
            inFlight.remove(path)
        }
        deferred.complete(composition)
        return composition
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
    private const val MAX_FAILED = 128
    private const val MAX_DECOMPRESSED_BYTES = 5L * 1024L * 1024L
}

/**
 * Smoke-build the Lottie layer tree on IO so we surface malformed shapes (e.g. a TGS
 * `RectangleShape` missing `position`/`size`/`cornerRadius`) as a deterministic null
 * here instead of crashing later on the main thread when Compose's first draw triggers
 * `LottieDrawable.setComposition` (RectangleContent.<init> → NPE on
 * `AnimatableFloatValue.createAnimation()`). `LottieDrawable.setComposition` is
 * Looper-free — safe to invoke on a worker thread.
 */
internal fun isCompositionRenderable(
    composition: LottieComposition,
    tag: String,
    label: String,
): Boolean = runCatching { LottieDrawable().setComposition(composition) }
    .onFailure { Log.w(tag, "lottie composition rejected (smoke build threw) for $label: ${it.message}") }
    .isSuccess
