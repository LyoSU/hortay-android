package dev.lyo.hortay.data.web

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Resolves Telegram custom-emoji ids to renderable assets via the public
 * `https://t.me/i/emoji/<id>.json` endpoint.
 *
 * Discovery: extracted from `https://telegram.org/js/widget-frame.js?66` — the script
 * Telegram serves with every t.me/s/ page calls this endpoint to hydrate the static
 * `<tg-emoji emoji-id="X">` placeholders into animated TGS / WebM / WebP renderings
 * after page load. The JSON shape and field names mirror that script directly.
 *
 * Why this matters for anonymous mode: without this resolver, `<tg-emoji>` placeholders
 * in static HTML carry only the id and we'd be forced to show neutral chips. With it,
 * web mode reaches full feature parity with TDLib mode for emoji rendering — same TGS
 * Lottie player, same WebM ExoPlayer, same WebP image pipeline. Custom-emoji REACTIONS
 * also become real glyphs, not "🎭" placeholders.
 *
 * Rate-limiting: a separate token bucket from [WebTelegramClient]. The two endpoints
 * are independent on Telegram's side (different services behind the t.me edge) and
 * empirically tolerate different traffic patterns. We default to a smaller per-request
 * delay here because emoji lookups happen in clusters (one feed page → 20-50 unique
 * ids) and CDN-side caching is aggressive (the JSON itself is identical across
 * requests for the same id; only the signed `?token=` in the returned CDN URL
 * eventually expires).
 *
 * Caching: the resolver keeps an in-memory `Map<String, ResolvedEmoji>`. Persistence
 * is intentionally NOT implemented in this primitive — the emoji-id → asset-type
 * mapping is immutable, but the embedded `?token=` query param on the CDN URL has a
 * TTL we don't know (probably 24h based on Telegram CDN convention). Persisting
 * therefore needs a TTL strategy; that lives in the Phase 2 step layer that wires
 * this resolver into a DataStore-backed cache.
 */
class WebCustomEmojiResolver(
    // No default value: instantiating with `WebTelegramClient.defaultHttpClient()`
    // here was actively wrong — it built a SECOND OkHttp client without the shared
    // disk cache, so every emoji resolution skipped the 10 MB ETag cache that
    // [AppGraph.webHttpClient] sets up. AppGraph already passes the shared client;
    // this signature change makes the wiring explicit so future call sites can't
    // silently re-acquire a cache-less twin.
    private val httpClient: OkHttpClient,
) {

    private val cache = HashMap<String, ResolvedEmoji>()
    private val cacheMutex = Mutex()

    // Per-request 200 ms spacing between outbound calls (5 req/s ceiling). Mirrors
    // WebTelegramClient's gate pattern but with a tighter bucket because /i/emoji is
    // much cheaper for Telegram (cached aggressively) and resolution clusters benefit
    // from going faster.
    private val gateUntilMs = AtomicLong(0L)

    /**
     * Resolve a single custom-emoji id. Returns null when the endpoint returns an
     * `error`, when the network call fails, or when JSON parsing fails. Errors are
     * logged but never thrown — callers iterate batches and skip unresolvable items.
     */
    suspend fun resolve(emojiId: String): ResolvedEmoji? {
        cacheMutex.withLock { cache[emojiId] }?.let { return it }

        awaitGate()
        val request = Request.Builder()
            .url("https://t.me/i/emoji/$emojiId.json")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            // Same gzip rule as WebTelegramClient: do NOT set Accept-Encoding; OkHttp's
            // BridgeInterceptor handles compression transparently when we leave it
            // alone, but opts out of decompression when we set it manually.
            .build()

        val resolved = runCatching { execute(request) }.fold(
            onSuccess = { response -> response.use { parseResponse(it, emojiId) } },
            onFailure = { error ->
                Log.w(TAG, "resolve($emojiId) network error: ${error.message}")
                null
            },
        )

        if (resolved != null) {
            cacheMutex.withLock { cache[emojiId] = resolved }
        }
        return resolved
    }

    /**
     * Resolve all ids in parallel, returning a map of successfully-resolved ones.
     * Failed ids are absent from the result rather than throwing — UI handles "no
     * resolution available" gracefully (renders count + neutral chip).
     *
     * Concurrency: each id gets its own coroutine, but [awaitGate] enforces the rate
     * limit globally so the cluster spreads out automatically. Pre-cached ids return
     * instantly without consuming gate tokens.
     */
    suspend fun resolveAll(emojiIds: Set<String>): Map<String, ResolvedEmoji> = coroutineScope {
        if (emojiIds.isEmpty()) return@coroutineScope emptyMap()
        emojiIds
            .map { id -> id to async { resolve(id) } }
            .associate { (id, deferred) -> id to deferred.await() }
            .filterValues { it != null }
            .mapValues { (_, value) -> value!! }
    }

    private fun parseResponse(response: Response, emojiId: String): ResolvedEmoji? {
        if (response.code != 200) {
            Log.w(TAG, "resolve($emojiId) HTTP ${response.code}")
            return null
        }
        val body = response.body?.string().orEmpty()
        val json = runCatching { JSON.decodeFromString<EmojiJson>(body) }.getOrNull()
        if (json == null) {
            Log.w(TAG, "resolve($emojiId) JSON parse failed (body=${body.take(200)})")
            return null
        }
        if (json.error != null) {
            Log.w(TAG, "resolve($emojiId) endpoint error: ${json.error}")
            return null
        }
        val type = when (json.type) {
            "tgs" -> ResolvedEmoji.Type.Tgs
            "webm" -> ResolvedEmoji.Type.Webm
            "webp" -> ResolvedEmoji.Type.Webp
            else -> {
                Log.w(TAG, "resolve($emojiId) unknown type ${json.type}")
                return null
            }
        }
        val url = json.emoji ?: run {
            Log.w(TAG, "resolve($emojiId) no emoji url field")
            return null
        }
        return ResolvedEmoji(
            emojiId = emojiId,
            type = type,
            url = url,
            thumbUrl = json.thumb,
            sizePx = json.size ?: when (type) {
                ResolvedEmoji.Type.Tgs -> 512
                else -> 100
            },
        )
    }

    private suspend fun awaitGate() {
        // Reserve a slot by atomically advancing the gate by REQUEST_SPACING_MS. Each
        // caller computes its own deadline from the previous deadline + spacing — this
        // is the classic "next-available-slot" token bucket without a coroutine
        // dispatcher. Threadsafe via updateAndGet.
        val now = System.currentTimeMillis()
        val deadline = gateUntilMs.updateAndGet { existing -> maxOf(existing, now) + REQUEST_SPACING_MS }
        val wait = deadline - now - REQUEST_SPACING_MS
        if (wait > 0) delay(wait)
    }

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response)
                    else response.close()
                }
            })
        }
    }

    /**
     * Clear the in-memory cache. Mostly for testing — production callers don't need
     * this because the cache is process-scoped and emoji-id → URL is stable enough
     * that we'd rather keep stale entries until the CDN token expires than re-fetch
     * on every cold start.
     */
    suspend fun clearCache() {
        cacheMutex.withLock { cache.clear() }
    }

    @Serializable
    private data class EmojiJson(
        val type: String? = null,
        val emoji: String? = null,
        val thumb: String? = null,
        val path: String? = null,
        val size: Int? = null,
        val error: String? = null,
    )

    companion object {
        private const val TAG = "WebCustomEmoji"
        // 200 ms = 5 req/s. Telegram's emoji JSON endpoint is CDN-cached so this is
        // conservative; we can probably go to 100 ms (10 req/s) but starting strict
        // and loosening with measurement is safer than the reverse.
        private const val REQUEST_SPACING_MS = 200L

        private val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

/**
 * Renderable custom-emoji asset resolved from the t.me JSON endpoint. Paired with
 * [WebReaction.emojiId] (or with `<tg-emoji>` ids extracted from post text) by the
 * UI layer so it can display the actual sticker instead of a neutral chip.
 */
data class ResolvedEmoji(
    val emojiId: String,
    val type: Type,
    /**
     * Absolute CDN URL to the rendered asset. Carries a signed `?token=` query —
     * value is treated as opaque and short-lived (re-fetch from [WebCustomEmojiResolver]
     * on stale-cache scenarios in higher layers).
     */
    val url: String,
    /** Optional thumbnail URL (PNG/SVG). Useful as a placeholder while [url] downloads. */
    val thumbUrl: String?,
    /** Asset's nominal pixel size; informs Compose layout. */
    val sizePx: Int,
) {
    enum class Type {
        /** Lottie-compatible TGS sticker. Renders via existing Lottie infrastructure. */
        Tgs,
        /** WebM video (animated). Renders via existing ExoPlayer pool. */
        Webm,
        /** Static WEBP image. Renders via Coil. */
        Webp,
    }
}
