package dev.lyo.hortay.data.discover

import dev.lyo.hortay.data.warnUnlessCancelled
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Source of the curated channel-suggestions catalog (see [SuggestionCatalog]).
 *
 * Resolution order, cheapest-first, on every [groups] call:
 *   1. in-memory catalog (kept once per process after the first successful network fetch),
 *   2. the last network payload persisted to [cacheFile] — served IMMEDIATELY, with a
 *      network refresh launched in the background (its result lands in memory for the
 *      next open),
 *   3. with no disk cache, a fresh network fetch from GitHub via jsDelivr, awaited,
 *   4. the tiny [FALLBACK] compiled into the app.
 *
 * A failed fetch is remembered for [FAILURE_BACKOFF_MS]: a blackholed CDN otherwise made
 * every open of the add-channel sheet wait out the full OkHttp connect + read timeouts
 * (~20 s) under [loadMutex] before falling back. Inside the backoff window the network is
 * skipped and the cache / fallback is served at once.
 *
 * The catalog is intentionally *remote* so the maintainer can add/retire channels
 * without an app release — public channels go dead or disable their `t.me/s`
 * preview over time. The fetch is a read-only GET of a public file; no user data
 * leaves the device (documented in README/SECURITY). Both guest and authenticated
 * modes use the same source; hydration of avatars / subscriber counts is what
 * differs per mode (t.me/s vs TDLib), not the catalog.
 *
 * [FALLBACK] guarantees the author's own channels always appear even with no
 * network and no cache; everything else rides on the remote list + disk cache.
 */
class ChannelSuggestionsRepository(
    private val http: OkHttpClient,
    private val cacheFile: File,
    private val appVersionCode: Int,
    private val catalogUrl: String = DEFAULT_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Owns the stale-while-revalidate refresh; process-lived like this repository. */
    private val refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val loadMutex = Mutex()

    @Volatile
    private var memory: SuggestionCatalog? = null

    /** Resolved, ordered, localized sections for [localeLang] (a language code). */
    suspend fun groups(localeLang: String): ImmutableList<SuggestedGroup> =
        catalog().resolve(localeLang, appVersionCode).toImmutableList()

    /** Wall-clock of the last failed network fetch; 0 = none. See [FAILURE_BACKOFF_MS]. */
    @Volatile
    private var lastFailureAtMs: Long = 0L

    /** Single-flight guard for the background refresh. Guarded by [loadMutex]. */
    private var refreshJob: Job? = null

    private suspend fun catalog(): SuggestionCatalog {
        memory?.let { return it }
        return loadMutex.withLock {
            memory?.let { return it }
            // Memoise only a fresh network catalog. The disk cache and [FALLBACK] are
            // stand-ins for an offline first open; pinning either in [memory] froze the
            // stale / tiny list for the whole process, so going online later never
            // fetched the real one. Not memoised, the next open retries the network
            // (once [FAILURE_BACKOFF_MS] has passed since a failure).
            val cached = readCache()
            if (cached != null) {
                // Stale-while-revalidate: the sheet paints from disk now; a fresh
                // catalog fetched in the background serves every later open.
                launchBackgroundRefreshLocked()
                return@withLock cached
            }
            if (inFailureBackoff()) return@withLock FALLBACK
            fetchAndRemember() ?: FALLBACK
        }
    }

    private fun inFailureBackoff(): Boolean {
        val failedAt = lastFailureAtMs
        return failedAt != 0L && nowMs() - failedAt < FAILURE_BACKOFF_MS
    }

    private fun launchBackgroundRefreshLocked() {
        if (refreshJob?.isActive == true || inFailureBackoff()) return
        refreshJob = refreshScope.launch { fetchAndRemember() }
    }

    private suspend fun fetchAndRemember(): SuggestionCatalog? {
        val fresh = fetchRemote()
        if (fresh != null) {
            memory = fresh
            lastFailureAtMs = 0L
        } else {
            lastFailureAtMs = nowMs()
        }
        return fresh
    }

    private suspend fun fetchRemote(): SuggestionCatalog? = withContext(ioDispatcher) {
        runCatching {
            val request = Request.Builder().url(catalogUrl).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                json.decodeFromString<SuggestionCatalog>(body).also {
                    runCatching { cacheFile.writeText(body) }
                        .warnUnlessCancelled(TAG, "cache write")
                }
            }
        }.warnUnlessCancelled(TAG, "fetchRemote").getOrNull()
    }

    private suspend fun readCache(): SuggestionCatalog? = withContext(ioDispatcher) {
        runCatching {
            if (!cacheFile.exists()) return@runCatching null
            json.decodeFromString<SuggestionCatalog>(cacheFile.readText())
        }.warnUnlessCancelled(TAG, "readCache").getOrNull()
    }

    companion object {
        private const val TAG = "ChannelSuggestions"

        /** How long a failed catalog fetch suppresses further network attempts. */
        const val FAILURE_BACKOFF_MS = 5 * 60 * 1000L
        const val DEFAULT_URL =
            "https://cdn.jsdelivr.net/gh/LyoSU/hortay-android@main/suggestions.json"

        /**
         * Minimal offline fallback. Deliberately tiny — it is NOT a mirror of the
         * remote list (that would defract the point of a remote catalog). It only
         * guarantees the author's channels surface with no network and no cache;
         * the full catalog arrives from [DEFAULT_URL] on the first online open.
         */
        val FALLBACK = SuggestionCatalog(
            version = 1,
            defaultLocale = "en",
            categories = listOf(
                CatalogCategory(
                    id = "featured",
                    order = 0,
                    title = mapOf("en" to "From the author", "uk" to "Канали від автора"),
                ),
            ),
            channels = listOf(
                CatalogChannel(
                    username = "LyBlog",
                    category = "featured",
                    locales = listOf("*"),
                    order = 0,
                    title = mapOf("en" to "LyChat"),
                    description = mapOf(
                        "en" to "AI, tech & personal notes",
                        "uk" to "AI, технології, особисте",
                    ),
                ),
                CatalogChannel(
                    username = "UAliveNews",
                    category = "featured",
                    locales = listOf("uk"),
                    order = 1,
                    description = mapOf(
                        "en" to "AI-curated Ukrainian news",
                        "uk" to "AI-агрегатор новин українських ЗМІ",
                    ),
                ),
            ),
        )
    }
}
