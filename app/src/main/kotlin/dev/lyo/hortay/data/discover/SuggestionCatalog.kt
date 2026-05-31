package dev.lyo.hortay.data.discover

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable

/**
 * Wire format for the curated channel-suggestions catalog. Fetched read-only from
 * GitHub (see [ChannelSuggestionsRepository]) and bundled as a tiny fallback. The
 * shape is documented in `/suggestions.schema.json` at the repo root.
 *
 * Forward-compatibility: parsed with `ignoreUnknownKeys = true`, so adding new
 * optional fields server-side never breaks an older client. Every field here has a
 * default so a partial / older payload still deserializes.
 *
 * Placement model: [channels] is a list of *placements*, not unique channels. The
 * same `username` may appear multiple times with different (`category`, `locales`)
 * so a channel can sit in a different section per language (e.g. `telegram` is
 * "Telegram/official" for `en` users but "World" for `uk` users). Within a single
 * resolved locale a username is de-duplicated, first placement wins.
 */
@Serializable
data class SuggestionCatalog(
    val version: Int = 1,
    val updated: String? = null,
    val defaultLocale: String = "en",
    val categories: List<CatalogCategory> = emptyList(),
    val channels: List<CatalogChannel> = emptyList(),
) {
    /**
     * Collapse the catalog into ordered, localized [SuggestedGroup]s for [localeLang]
     * (a two-letter language code such as "uk" / "en"). [appVersionCode] gates
     * placements that declare a higher `minVersionCode`.
     *
     * Locale resolution mirrors the schema doc: if the device language has any
     * placement of its own, the user sees `[deviceLang] + "*"`; otherwise the user
     * sees `[defaultLocale] + "*"`. This guarantees non-Ukrainian users never get
     * Ukrainian-only channels, while still falling back to a useful English set.
     */
    fun resolve(localeLang: String, appVersionCode: Int): List<SuggestedGroup> {
        val lang = localeLang.lowercase()
        val gated = channels.filter { appVersionCode >= it.minVersionCode }
        val hasNative = gated.any { lang in it.locales }
        val effective = if (hasNative) lang else defaultLocale.lowercase()

        fun CatalogChannel.matches() = locales.any { it == "*" || it.lowercase() == effective }

        val byCategory = gated.filter { it.matches() }
            .groupBy { it.category }

        val orderedCats = categories.sortedWith(compareBy({ it.order }, { it.id }))
        // Categories referenced by a channel but missing from `categories` still
        // render (defensive against a half-edited catalog) — appended after the
        // declared ones in id order, titled by their raw id.
        val knownIds = categories.map { it.id }.toSet()
        val orphanCats = byCategory.keys.filter { it !in knownIds }.sorted()
            .map { CatalogCategory(id = it, order = Int.MAX_VALUE) }

        return (orderedCats + orphanCats).mapNotNull { cat ->
            val placements = byCategory[cat.id].orEmpty()
            if (placements.isEmpty()) return@mapNotNull null
            val seen = HashSet<String>()
            val resolved = placements
                .sortedWith(compareBy({ it.order }, { it.username.lowercase() }))
                .mapNotNull { ch ->
                    if (!seen.add(ch.username.lowercase())) return@mapNotNull null
                    SuggestedChannel(
                        username = ch.username,
                        titleOverride = ch.title.pick(effective, defaultLocale),
                        description = ch.description.pick(effective, defaultLocale).orEmpty(),
                    )
                }
            if (resolved.isEmpty()) null
            else SuggestedGroup(
                categoryId = cat.id,
                title = cat.title.pick(effective, defaultLocale) ?: cat.id,
                channels = resolved.toImmutableList(),
            )
        }
    }
}

@Serializable
data class CatalogCategory(
    val id: String,
    val order: Int = 0,
    val title: Map<String, String> = emptyMap(),
)

@Serializable
data class CatalogChannel(
    val username: String,
    val category: String = "world",
    val locales: List<String> = listOf("*"),
    val order: Int = 0,
    val title: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val minVersionCode: Int = 0,
)

/** Localized-string picker: exact locale → defaultLocale → any value → null. */
private fun Map<String, String>.pick(locale: String, default: String): String? =
    this[locale] ?: this[default.lowercase()] ?: this["en"] ?: values.firstOrNull()

/** One section of suggestions, already localized and ordered. */
@Immutable
data class SuggestedGroup(
    val categoryId: String,
    val title: String,
    val channels: ImmutableList<SuggestedChannel>,
)

/**
 * One suggested channel as the UI consumes it. [titleOverride] is the catalog's
 * optional display name; when null the live channel title (t.me/s or TDLib) is
 * shown once hydration lands. [description] is the editorial blurb.
 */
@Immutable
data class SuggestedChannel(
    val username: String,
    val titleOverride: String?,
    val description: String,
)
