package dev.lyo.hortay.data.web

import android.util.Log
import dev.lyo.hortay.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.Locale
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
     * Fetch one page of a channel preview.
     *
     * Conditional GET (ETag / Last-Modified) is delegated entirely to OkHttp's
     * disk [Cache] (configured by the caller in [defaultHttpClient] / AppGraph).
     * OkHttp persists validators across cold starts, sends If-None-Match /
     * If-Modified-Since automatically on every request, and serves the cached
     * body transparently when the server responds 304. We detect "not modified"
     * by inspecting [Response.networkResponse]: when its code is 304, the wire
     * confirmed our cached body is still valid and the caller can skip parsing.
     *
     * @param username channel handle without leading `@`. Caller is responsible for
     *   sanitizing user input (strip `@`, parse out of `t.me/<u>` links) — see
     *   [parseUsernameFromInput].
     * @param before paginate older posts: pass [WebChannelPage.olderCursor] from a
     *   previous result. null fetches the latest page.
     * @param useCache when true (default), let OkHttp serve from cache + revalidate.
     *   Pull-to-refresh and channel-lookup set this false to force a fresh body
     *   regardless of validators.
     */
    suspend fun fetchChannelPage(
        username: String,
        before: String? = null,
        useCache: Boolean = true,
    ): FetchResult {
        awaitGate()

        val url = buildUrl(username, before)

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
            // Accept-Language follows the system locale rather than a hardcoded value.
            // A static "en-US,en;q=0.9,uk;q=0.8" sent by every Hortay user would brand
            // a Polish, German, or Spanish device with a Ukrainian fallback — that's a
            // unique fingerprint bit, not a neutral default. `Locale.getDefault()` is
            // what a real browser on the same device would send, so we add zero
            // distinguishing entropy on top of what the user already leaks.
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .apply {
                if (!useCache) {
                    cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                }
            }
            .build()

        return runCatching { execute(request) }.fold(
            onSuccess = { response ->
                response.use { handleResponse(it, username) }
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
     *
     * Hard-capped at [LOOKUP_TIMEOUT_MS] so a stuck rate-limit gate (a sweep just
     * collected a 429 and pushed `gateUntilMs` 120 s out, the user then opens
     * Add-channel) doesn't freeze the UI for two minutes with no recourse —
     * the user gets a clear RateLimited result and can retry instead of
     * staring at a "Validating…" spinner that looks like the app died.
     */
    suspend fun lookupChannel(username: String): LookupResult {
        val result = try {
            kotlinx.coroutines.withTimeout(LOOKUP_TIMEOUT_MS) {
                fetchChannelPage(username, useCache = false)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // The most common cause is the rate-limit gate: surface that
            // explicitly so the UI can show "rate limited, try in N s" using
            // the deadline already on [gateUntilMs]. Falling back to network
            // error would be a worse UX (looks like a connectivity problem).
            val remainingMs = (gateUntilMs.get() - System.currentTimeMillis()).coerceAtLeast(0L)
            return if (remainingMs > 0L) {
                LookupResult.RateLimited(remainingMs)
            } else {
                LookupResult.NetworkError(java.io.IOException("Lookup timed out"))
            }
        }
        return when (result) {
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
                // lookupChannel always uses useCache=false (FORCE_NETWORK), so OkHttp
                // can't return a 304 here — the only way to land in this branch would
                // be a server-side bug. Treat as NotFound for safety.
                LookupResult.NotFound
            }
            is FetchResult.ParseFailure -> LookupResult.ParseFailure
        }
    }

    private suspend fun handleResponse(
        response: Response,
        username: String,
    ): FetchResult {
        // OkHttp's Cache transparently serves a cached body on 304 — the visible
        // response.code is 200 even when the wire returned 304. Inspecting
        // networkResponse lets us detect "not modified" so the caller can skip
        // re-parsing 100KB of HTML it already has. Only meaningful when the
        // request was allowed to use cache; FORCE_NETWORK requests will always
        // see networkResponse with the actual wire code.
        if (response.networkResponse?.code == 304) {
            return FetchResult.NotModified
        }
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
                return FetchResult.Page(
                    page = page,
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified"),
                )
            }
            404 -> return FetchResult.NotFound
            // Telegram's edge sends BOTH shapes for "no /s/ preview available":
            //   • 403 — historic / rare. Still kept as a fallback.
            //   • 301/302 with Location: t.me/<u> (no `/s/`) — the modern path,
            //     fires for private channels AND for handles that resolve to a
            //     user/bot rather than a channel. With followRedirects=false in
            //     [defaultHttpClient] (intentional, so we can detect the shape),
            //     we receive the bare redirect and must classify it ourselves.
            //     A redirect to anywhere else (rare; Telegram's fault) maps to
            //     a generic NetworkError so we don't silently absorb breaking
            //     edge changes as "private". Without this branch, the 302 path
            //     fell through to "else → NetworkError(IOException(\"HTTP 302\"))"
            //     and the user saw "network error, retry" instead of the correct
            //     "private channel" CTA.
            403 -> return FetchResult.PrivateChannel
            301, 302, 307, 308 -> {
                val location = response.header("Location").orEmpty()
                val redirectsToBareTme = location.contains("t.me/") &&
                    !location.contains("t.me/s/")
                return if (redirectsToBareTme) FetchResult.PrivateChannel
                else FetchResult.NetworkError(IOException("HTTP ${response.code} → $location"))
            }
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
        // Loop, not single-shot: with [fetchSemaphore] permitting up to 6 concurrent
        // fetchChannelPage calls, all six can read [gateUntilMs] before the first
        // one of them hits a 429 and pushes a fresh deadline. Without re-checking,
        // the other five sail past awaitGate and fire requests into the rate-limit
        // window — earning five more 429s and pushing the gate further out for
        // every other in-flight request. Reading after each delay closes the race:
        // any 429 issued during our wait extends the deadline and we keep sleeping
        // until the deadline truly is in the past.
        while (true) {
            val until = gateUntilMs.get()
            val now = System.currentTimeMillis()
            if (until <= now) return
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
        // No app-identifying suffix: contradicts the "anonymous" promise — a
        // `Hortay/<version>` tail makes every guest-mode request trivially
        // attributable in Telegram's edge logs (and to anyone else on-path).
        // We emit a plain Chrome-on-Linux UA, indistinguishable from a real
        // browser at the HTTP layer.
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        // Computed once at class-load — Locale changes are rare enough at runtime
        // (system-settings round trip) that a per-request alloc isn't justified, and
        // a refresh on every fetch would in turn become a fingerprint bit if Locale
        // changed mid-session. Format mirrors what a desktop browser sends:
        // "<tag>,<tag>;q=0.9,en;q=0.8" — primary preference, then English fallback so
        // pages that don't have the user's locale degrade to readable English instead
        // of whatever Telegram's edge picks by IP.
        private val ACCEPT_LANGUAGE: String = run {
            val tag = Locale.getDefault().toLanguageTag()
            if (tag.equals("en", ignoreCase = true) || tag.startsWith("en-", ignoreCase = true)) {
                "$tag,en;q=0.9"
            } else {
                "$tag,en;q=0.8"
            }
        }

        // Cap on per-429 backoff. Picked to stay below user-visible timeout perception
        // (5 min is already very long) while honoring Telegram's signal. Shorter than
        // TdClient's FLOOD_WAIT_CAP because t.me/s/ recovers faster than MTProto floods.
        private const val MAX_BACKOFF_SEC = 120L
        private const val DEFAULT_BACKOFF_SEC = 30L

        // Cap on [lookupChannel] so the AddChannelSheet can't deadlock the UI
        // for the full ~120 s rate-limit gate. 15 s lets a slow but live
        // 3G connection complete a real lookup; longer than that is almost
        // always a stuck gate or a dropped connection — neither benefits from
        // continued waiting.
        private const val LOOKUP_TIMEOUT_MS = 15_000L
        // Treat 5xx as a transient signal: short backoff, retry on next poll cycle.
        private const val SERVER_ERROR_BACKOFF_SEC = 10L

        /**
         * Build the default HTTP client. Pass a [cacheDir] to enable disk-backed
         * conditional GET (recommended) — typically the app's
         * `Context.cacheDir / "web-http"`. When null, no HTTP cache is used and
         * every fetch is a full body download.
         *
         * Cache size: 10 MB. Enough room for ~150 channel preview pages (each
         * ~30-100 KB compressed). DiskLruCache evicts least-recently-used entries
         * when full so a 200-channel rotation degrades gracefully.
         */
        fun defaultHttpClient(cacheDir: File? = null): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                // Aggressive connection reuse during a sweep: 200-channel fan-out
                // benefits from a wider pool than OkHttp's default 5. Idle keep-
                // alive shortened from 5 minutes → 60 seconds because tier-2
                // sweeps fire at 5-minute intervals at most: a 5-minute idle
                // window keeps 8 sockets alive across the *entire* gap between
                // sweeps, draining radio "tail" energy for nothing. 60 seconds
                // amortises the connection setup over a single sweep but lets
                // sockets close cleanly between them.
                .connectionPool(okhttp3.ConnectionPool(8, 60, TimeUnit.SECONDS))
                // Disable redirect-following for /s/<u> probes so we can detect the
                // "private channel" redirect (t.me/s/foo → t.me/foo) cleanly. We then
                // surface it as PrivateChannel rather than chasing the destination.
                .followRedirects(false)
                .followSslRedirects(false)
            if (cacheDir != null) {
                if (!cacheDir.exists()) cacheDir.mkdirs()
                builder.cache(Cache(cacheDir, HTTP_CACHE_SIZE_BYTES))
            }
            return builder.build()
        }

        private const val HTTP_CACHE_SIZE_BYTES = 10L * 1024 * 1024
    }
}

/** Result of a single fetch. Covers every branch the scheduler needs to handle. */
sealed interface FetchResult {
    /**
     * Successful fetch with a parsed page. [etag] / [lastModified] are surfaced so
     * [dev.lyo.hortay.data.web.WebRepository.ingestPage] can persist them — even
     * though OkHttp's cache also stores validators on disk, having them in the DB
     * lets us debug "did this fetch revalidate?" without an OkHttp Cache dump.
     */
    data class Page(
        val page: WebChannelPage,
        val etag: String?,
        val lastModified: String?,
    ) : FetchResult
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
 * Telegram historically required 5-32 chars; Fragment-auctioned and Premium-short
 * usernames now go as short as 2 characters (e.g. `@io`, `@no`). We encode the
 * loosest bound here — must start with a letter, ASCII letters/digits/underscores,
 * length 2-32 — so an obvious typo still fails fast without a network round-trip,
 * but legitimate short handles aren't pre-rejected by us before t.me has a say.
 *
 * Result is lowercased: Telegram treats `@Durov` and `@durov` as the same handle
 * server-side, but our SQLite primary key on `channel.username` is case-sensitive
 * by default. Without normalising at the boundary, "Durov" and "durov" would
 * become two distinct subscriptions pointing at the same channel — both fetching,
 * both visible in the channels list, both showing the same content twice in the
 * feed. Normalising here is the single point that catches every entry path
 * (manual paste, curated tap, deep link, clipboard auto-paste).
 */
fun parseUsernameFromInput(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    // tg://resolve?domain=<name>
    Regex("""^tg://resolve\?(?:.*&)?domain=([A-Za-z][A-Za-z0-9_]{1,31})\b""")
        .find(trimmed)
        ?.let { return it.groupValues[1].lowercase() }

    // https://t.me/<name>(/<msg>)? or t.me/<name>
    Regex("""^(?:https?://)?t\.me/(?:s/)?([A-Za-z][A-Za-z0-9_]{1,31})(?:/\d+)?/?$""")
        .find(trimmed)
        ?.let { return it.groupValues[1].lowercase() }

    // @<name> or bare <name>
    val bare = trimmed.removePrefix("@")
    if (bare.matches(Regex("""[A-Za-z][A-Za-z0-9_]{1,31}"""))) return bare.lowercase()

    return null
}
