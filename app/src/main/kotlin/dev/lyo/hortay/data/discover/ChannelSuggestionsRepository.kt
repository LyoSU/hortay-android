package dev.lyo.hortay.data.discover

import dev.lyo.hortay.data.warnUnlessCancelled
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
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
 *   1. in-memory catalog (parsed once per process after the first successful load),
 *   2. a fresh network fetch from GitHub via jsDelivr,
 *   3. the last network payload persisted to [cacheFile],
 *   4. the tiny [FALLBACK] compiled into the app.
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

    private suspend fun catalog(): SuggestionCatalog {
        memory?.let { return it }
        return loadMutex.withLock {
            memory?.let { return it }
            val loaded = fetchRemote() ?: readCache() ?: FALLBACK
            memory = loaded
            loaded
        }
    }

    private suspend fun fetchRemote(): SuggestionCatalog? = withContext(Dispatchers.IO) {
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

    private suspend fun readCache(): SuggestionCatalog? = withContext(Dispatchers.IO) {
        runCatching {
            if (!cacheFile.exists()) return@runCatching null
            json.decodeFromString<SuggestionCatalog>(cacheFile.readText())
        }.warnUnlessCancelled(TAG, "readCache").getOrNull()
    }

    companion object {
        private const val TAG = "ChannelSuggestions"
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
