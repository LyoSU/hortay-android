package dev.lyo.hortay.data.web

import android.util.Log
import dev.lyo.hortay.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP-level access to `https://t.me/s/<channel>` for the anonymous web pipeline.
 *
 * Responsibilities:
 *   - Authenticated-looking GET to a public channel preview page.
 *   - Conditional GET via `If-Modified-Since` and `If-None-Match` to keep 200+ channel
 *     polling within reason. A 304 reply costs ~200 bytes vs ~30 KB for a full body —
 *     this is the single architectural lever that makes web-mode scale.
 *   - Global rate-limit gate: one token bucket guarding all requests, exponential
 *     backoff on 429. Mirrors [dev.lyo.hortay.data.TdClient.floodWaitUntilMs] semantics
 *     but on a per-process basis (not per-API-method) — t.me/s/ has one bucket.
 *   - Defensive parsing via [TmePageParser]; rendering layout regressions surface as
 *     [LookupResult.ParseFailure] rather than crashes.
 *
 * Non-responsibilities (handled elsewhere):
 *   - Scheduling / when to poll → `WebFeedScheduler` (Phase 1.5).
 *   - Subscription persistence → `SubscriptionsStore` (Phase 2).
 *   - HTML → TimelinePost adaptation → `WebFeedSource` (Phase 2).
 *
 * User-Agent rationale: a real-looking mobile browser UA is required. Using OkHttp's
 * default UA ("okhttp/4.x") causes Telegram's edge to occasionally serve a stripped-down
 * fallback page or 4xx outright. We pick a stable Chrome-on-Android string and append a
 * short Hortay tag so anyone analyzing logs can identify the traffic source if needed.
 */
class WebTelegramClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    /**
     * Single global deadline used by both 429 and connection-error backoff. Shared with
     * [awaitGate] before each request so concurrent fetches all suspend together rather
     * than each tripping a separate 429.
     */
    private val gateUntilMs = AtomicLong(0L)

    /**
     * Most-recent fetch metadata per channel — used to drive the next conditional GET.
     * Tiny memory footprint (a few hundred bytes per channel) and process-scoped:
     * persistence across launches isn't worth a DataStore round-trip when a 304-vs-200
     * miss costs us 30 KB once, recovered on the next sweep.
     */
    private val cacheState: MutableMap<String, CacheState> = HashMap()

    /**
     * Fetch one page of a channel preview.
     *
     * @param username channel handle without leading `@`. Caller is responsible for
     *   sanitizing user input (strip `@`, parse out of `t.me/<u>` links) — see
     *   [parseUsernameFromInput].
     * @param before paginate older posts: pass [WebChannelPage.olderCursor] from a
     *   previous result. null fetches the latest page.
     * @param useCache when true (default), apply stored ETag/Last-Modified for the
     *   conditional GET. Pull-to-refresh sets this false to force a fresh body.
     */
    suspend fun fetchChannelPage(
        username: String,
        before: String? = null,
        useCache: Boolean = true,
    ): FetchResult {
        awaitGate()

        val url = buildUrl(username, before)
        val cacheKey = if (before == null) username else "$username?before=$before"
        val cached = if (useCache) cacheState[cacheKey] else null

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            // Do NOT set Accept-Encoding here. OkHttp's BridgeInterceptor adds
            // `Accept-Encoding: gzip` automatically AND transparently gunzips the
            // response body — but only when we don't set the header ourselves. Setting
            // it manually opts out of that automatic decompression, leaving us with
            // raw gzipped bytes that look like binary garbage to the parser. Verified
            // on-device: with the header set we got 20 KB of gzip magic; without it,
            // 136 KB of valid HTML.
            .header("Accept-Language", "en-US,en;q=0.9,uk;q=0.8")
            .apply {
                cached?.etag?.let { header("If-None-Match", it) }
                cached?.lastModified?.let { header("If-Modified-Since", it) }
            }
            .build()

        return runCatching { execute(request) }.fold(
            onSuccess = { response ->
                response.use { handleResponse(it, username, cacheKey) }
            },
            onFailure = { error ->
                Log.w(TAG, "fetchChannelPage(${username}) failed: ${error.message}")
                FetchResult.NetworkError(error)
            },
        )
    }

    /**
     * Cheap "does this channel exist?" probe used by [AddChannelScreen] to validate user
     * input before subscribing. Reuses [fetchChannelPage] but classifies the outcome
     * differently — we care about validity, not freshness.
     */
    suspend fun lookupChannel(username: String): LookupResult {
        return when (val result = fetchChannelPage(username, useCache = false)) {
            is FetchResult.Page -> {
                if (result.page.posts.isEmpty()) {
                    LookupResult.Empty(result.page.channel)
                } else {
                    LookupResult.Found(result.page.channel)
                }
            }
            FetchResult.NotFound -> LookupResult.NotFound
            FetchResult.PrivateChannel -> LookupResult.Private
            is FetchResult.RateLimited -> LookupResult.RateLimited(result.retryAfterMs)
            is FetchResult.NetworkError -> LookupResult.NetworkError(result.cause)
            FetchResult.NotModified -> {
                // useCache=false above means we should never hit this path — if Telegram
                // somehow returns 304 anyway, treat as Found via stale cache (best-effort).
                cacheState[username]?.lastPage?.channel
                    ?.let { LookupResult.Found(it) }
                    ?: LookupResult.NotFound
            }
            is FetchResult.ParseFailure -> LookupResult.ParseFailure
        }
    }

    private suspend fun handleResponse(
        response: Response,
        username: String,
        cacheKey: String,
    ): FetchResult {
        when (response.code) {
            200 -> {
                val body = response.body?.string().orEmpty()
                val page = TmePageParser.parse(body, username)
                if (page == null) {
                    Log.w(TAG, "Parse failed for $username (body length=${body.length})")
                    if (BuildConfig.DEBUG) {
                        // Diagnostic: when the parser declines a 200 response, the body
                        // is almost always Telegram serving us a different page variant
                        // (e.g. mobile landing, login wall) due to UA / TLS fingerprint
                        // sniffing. The first 400 chars typically contain the <title>
                        // and meta tags that identify which variant — enough to
                        // diagnose without dumping all 100+ KB into logcat.
                        Log.w(TAG, "  head: ${body.take(400).replace('\n', ' ')}")
                        Log.w(TAG, "  ctype: ${response.header("Content-Type")} server: ${response.header("Server")}")
                    }
                    return FetchResult.ParseFailure
                }
                cacheState[cacheKey] = CacheState(
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified"),
                    lastPage = page,
                )
                return FetchResult.Page(page)
            }
            304 -> {
                val stale = cacheState[cacheKey]?.lastPage
                return if (stale != null) FetchResult.NotModified
                else FetchResult.NetworkError(IllegalStateException("304 without prior cache"))
            }
            404 -> return FetchResult.NotFound
            403 -> return FetchResult.PrivateChannel
            429 -> {
                val retrySec = response.header("Retry-After")?.toLongOrNull() ?: DEFAULT_BACKOFF_SEC
                val capped = retrySec.coerceAtMost(MAX_BACKOFF_SEC)
                pushGate(capped * 1000L)
                Log.w(TAG, "429 from t.me/s/$username — backing off ${capped}s")
                return FetchResult.RateLimited(capped * 1000L)
            }
            in 500..599 -> {
                pushGate(SERVER_ERROR_BACKOFF_SEC * 1000L)
                return FetchResult.NetworkError(IOException("HTTP ${response.code}"))
            }
            else -> return FetchResult.NetworkError(IOException("HTTP ${response.code}"))
        }
    }

    private fun buildUrl(username: String, before: String?): String {
        val base = "https://t.me/s/$username".toHttpUrl().newBuilder()
        if (before != null) base.addQueryParameter("before", before)
        return base.build().toString()
    }

    private suspend fun awaitGate() {
        val until = gateUntilMs.get()
        val now = System.currentTimeMillis()
        if (until > now) {
            delay(until - now)
        }
    }

    private fun pushGate(durationMs: Long) {
        val deadline = System.currentTimeMillis() + durationMs
        gateUntilMs.updateAndGet { existing -> maxOf(existing, deadline) }
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

    private data class CacheState(
        val etag: String?,
        val lastModified: String?,
        val lastPage: WebChannelPage,
    )

    companion object {
        private const val TAG = "WebTelegram"

        // Desktop-Chrome UA. Telegram's edge serves THREE different versions of t.me/<u>:
        //   • mobile UA → ~2 KB "Open in app" landing page
        //   • OkHttp default / generic → ~20 KB partial template (no channel history)
        //   • desktop browser UA → ~136 KB full /s/ preview with posts
        // We need the third one. Empirically verified against 8 channels (durov,
        // telegram, nexta_live, …) at the time of authoring; if Telegram changes their
        // UA-sniffing rules, [TmePageParser] will detect the missing
        // .tgme_channel_info_header_title and surface ParseFailure to the caller.
        // The trailing Hortay marker is preserved so server-side log analysis can
        // attribute the traffic if needed.
        private val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36 " +
            "Hortay/${BuildConfig.VERSION_NAME}"

        // Cap on per-429 backoff. Picked to stay below user-visible timeout perception
        // (5 min is already very long) while honoring Telegram's signal. Shorter than
        // TdClient's FLOOD_WAIT_CAP because t.me/s/ recovers faster than MTProto floods.
        private const val MAX_BACKOFF_SEC = 120L
        private const val DEFAULT_BACKOFF_SEC = 30L
        // Treat 5xx as a transient signal: short backoff, retry on next poll cycle.
        private const val SERVER_ERROR_BACKOFF_SEC = 10L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            // Aggressive connection reuse: 200-channel sweep wants pooling. Default is
            // 5 — bumped to keep the t.me edge connection warm during a sweep.
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            // Disable redirect-following for /s/<u> probes so we can detect the
            // "private channel" redirect (t.me/s/foo → t.me/foo) cleanly. We then
            // surface it as PrivateChannel rather than chasing the destination.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** Result of a single fetch. Covers every branch the scheduler needs to handle. */
sealed interface FetchResult {
    data class Page(val page: WebChannelPage) : FetchResult
    /** Conditional GET hit — caller's existing data is still valid. */
    data object NotModified : FetchResult
    /** Channel doesn't exist or was deleted. */
    data object NotFound : FetchResult
    /**
     * Channel exists but isn't publicly viewable. t.me/s/<u> 302-redirects to
     * t.me/<u> in this case, which we don't follow.
     */
    data object PrivateChannel : FetchResult
    data class RateLimited(val retryAfterMs: Long) : FetchResult
    data class NetworkError(val cause: Throwable) : FetchResult
    /** HTML returned but parser couldn't make sense of it. Telegram changed something. */
    data object ParseFailure : FetchResult
}

/** Result of [WebTelegramClient.lookupChannel] — a UX-shaped subset of [FetchResult]. */
sealed interface LookupResult {
    data class Found(val channel: WebChannelInfo) : LookupResult
    /** Channel exists but had no posts visible. Still a valid subscription target. */
    data class Empty(val channel: WebChannelInfo) : LookupResult
    data object NotFound : LookupResult
    data object Private : LookupResult
    data class RateLimited(val retryAfterMs: Long) : LookupResult
    data class NetworkError(val cause: Throwable) : LookupResult
    data object ParseFailure : LookupResult
}

/**
 * Smart-paste helper for [AddChannelScreen]. Accepts:
 *   - "@durov"
 *   - "durov"
 *   - "https://t.me/durov"
 *   - "t.me/durov"
 *   - "https://t.me/durov/123" (post link — strips message id)
 *   - "tg://resolve?domain=durov"
 *
 * Returns null when the input doesn't match a Telegram public username pattern.
 * Telegram's actual rule is ASCII letters/digits/underscores, length 5-32, must start
 * with a letter; we encode that here so an obviously-broken input fails fast without
 * a network round-trip.
 */
fun parseUsernameFromInput(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    // tg://resolve?domain=<name>
    Regex("""^tg://resolve\?(?:.*&)?domain=([A-Za-z][A-Za-z0-9_]{4,31})\b""")
        .find(trimmed)
        ?.let { return it.groupValues[1] }

    // https://t.me/<name>(/<msg>)? or t.me/<name>
    Regex("""^(?:https?://)?t\.me/(?:s/)?([A-Za-z][A-Za-z0-9_]{4,31})(?:/\d+)?/?$""")
        .find(trimmed)
        ?.let { return it.groupValues[1] }

    // @<name> or bare <name>
    val bare = trimmed.removePrefix("@")
    if (bare.matches(Regex("""[A-Za-z][A-Za-z0-9_]{4,31}"""))) return bare

    return null
}
