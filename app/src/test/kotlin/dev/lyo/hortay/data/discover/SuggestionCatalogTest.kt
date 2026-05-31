package dev.lyo.hortay.data.discover

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the locale-resolution + grouping contract of [SuggestionCatalog.resolve]:
 * non-native locales never leak native-only channels, wildcards reach everyone,
 * sections keep their declared order, and a handle placed in two locale-specific
 * sections de-duplicates to one row per resolved locale.
 */
class SuggestionCatalogTest {

    private val catalog = SuggestionCatalog(
        version = 1,
        defaultLocale = "en",
        categories = listOf(
            CatalogCategory("featured", order = 0, title = mapOf("en" to "Featured", "uk" to "Автор")),
            CatalogCategory("news", order = 1, title = mapOf("en" to "News", "uk" to "Новини")),
            CatalogCategory("world", order = 2, title = mapOf("en" to "World", "uk" to "Світові")),
        ),
        channels = listOf(
            CatalogChannel("LyBlog", "featured", locales = listOf("*"), order = 0),
            CatalogChannel("UAliveNews", "featured", locales = listOf("uk"), order = 1),
            CatalogChannel("suspilnenews", "news", locales = listOf("uk")),
            CatalogChannel("guardian", "news", locales = listOf("en")),
            // Same handle placed once per locale in different sections.
            CatalogChannel("telegram", "world", locales = listOf("uk"), description = mapOf("uk" to "Офіційні")),
            CatalogChannel("telegram", "news", locales = listOf("en"), description = mapOf("en" to "Official")),
            // Future-gated: hidden from current clients.
            CatalogChannel("futureonly", "news", locales = listOf("*"), minVersionCode = 999_999),
        ),
    )

    private fun usernames(groups: List<SuggestedGroup>) =
        groups.flatMap { it.channels }.map { it.username }

    @Test
    fun `uk sees ukrainian channels and wildcards but not en-only`() {
        val groups = catalog.resolve("uk", appVersionCode = 100)
        val names = usernames(groups)
        assertTrue("LyBlog" in names)       // wildcard
        assertTrue("UAliveNews" in names)    // uk-only
        assertTrue("suspilnenews" in names)
        assertTrue("telegram" in names)      // world/uk placement
        assertFalse("guardian" in names)     // en-only
        // Section titles localized to uk, declared order preserved.
        assertEquals(listOf("Автор", "Новини", "Світові"), groups.map { it.title })
    }

    @Test
    fun `en sees english set and wildcards but not uk-only`() {
        val names = usernames(catalog.resolve("en", appVersionCode = 100))
        assertTrue("LyBlog" in names)
        assertTrue("guardian" in names)
        assertTrue("telegram" in names)       // news/en placement
        assertFalse("UAliveNews" in names)    // uk-only
        assertFalse("suspilnenews" in names)
    }

    @Test
    fun `unknown locale falls back to defaultLocale set`() {
        val names = usernames(catalog.resolve("de", appVersionCode = 100))
        // de has no native channels -> behaves like en.
        assertTrue("guardian" in names)
        assertFalse("UAliveNews" in names)
        assertFalse("suspilnenews" in names)
    }

    @Test
    fun `a handle is not duplicated within a resolved locale`() {
        val names = usernames(catalog.resolve("uk", appVersionCode = 100))
        assertEquals(1, names.count { it == "telegram" })
    }

    @Test
    fun `minVersionCode gates entries above the running version`() {
        val names = usernames(catalog.resolve("en", appVersionCode = 100))
        assertFalse("futureonly" in names)
        val unlocked = usernames(catalog.resolve("en", appVersionCode = 1_000_000))
        assertTrue("futureonly" in unlocked)
    }

    @Test
    fun `empty sections are dropped`() {
        // A catalog whose only channel is uk-only -> en resolve yields no groups.
        val ukOnly = catalog.copy(
            channels = listOf(CatalogChannel("suspilnenews", "news", locales = listOf("uk"))),
        )
        assertTrue(ukOnly.resolve("en", appVersionCode = 100).isEmpty())
    }
}
