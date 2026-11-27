package dev.lyo.hortay.data.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** [GuestChannelIndex]: the guest Add-channel sheet's offline name search. */
class GuestChannelSearchTest {

    private fun candidate(
        username: String,
        title: String? = null,
        source: GuestChannelSource = GuestChannelSource.Known,
        description: String? = null,
        avatarUrl: String? = null,
        subscribers: String? = null,
        count: Int = 1,
    ) = GuestChannelCandidate(
        username = username,
        title = title,
        description = description,
        avatarUrl = avatarUrl,
        subscribers = subscribers,
        source = source,
        count = count,
    )

    private fun index(vararg c: GuestChannelCandidate) = GuestChannelIndex.build(c.toList())

    private fun GuestChannelIndex.names(query: String) = search(query).map { it.channel.username }

    @Nested
    inner class Folding {
        @Test
        fun `case, accents and yo fold, separators collapse`() {
            assertEquals("cafe creme", FoldedText.of("  Café__Crème!! ").text)
            assertEquals("еж", FoldedText.of("Ёж").text)
            assertEquals("news 24", FoldedText.of("🔥 NEWS · 24").text)
        }

        @Test
        fun `original ranges survive folding`() {
            val f = FoldedText.of("🔥 Café")
            // Folded "cafe"; "fe" sits at folded 2..3 and maps back to the "fé" chars.
            val r = f.originalRange(2, 4)
            assertEquals("fé", "🔥 Café".substring(r.first, r.last + 1))
        }

        @Test
        fun `matching ignores accents and case both ways`() {
            val idx = index(candidate("cafechan", title = "Café Crème"))
            assertEquals(listOf("cafechan"), idx.names("CAFE creme"))
            assertEquals(listOf("cafechan"), idx.names("crème"))
        }

        @Test
        fun `leading at sign is ignored`() {
            val idx = index(candidate("durov", title = "Durov's Channel"))
            assertEquals(GuestMatchTier.ExactUsername, idx.search("@durov").single().tier)
        }
    }

    @Nested
    inner class LayoutSwap {
        @Test
        fun `latin typed on a Ukrainian keyboard finds the Cyrillic title`() {
            val idx = index(candidate("hello_ua", title = "Привіт, світе"))
            val hit = idx.search("ghbdsn").single()
            assertEquals("hello_ua", hit.channel.username)
            assertTrue(hit.viaLayoutSwap)
        }

        @Test
        fun `Russian layout variant is tried too`() {
            val idx = index(candidate("ru_hello", title = "Привыт"))
            assertEquals(listOf("ru_hello"), idx.names("ghbdsn"))
        }

        @Test
        fun `Cyrillic typed on a QWERTY keyboard finds the Latin username`() {
            val idx = index(candidate("hello", title = "Hello World"))
            val hit = idx.search("руддщ").single()
            assertTrue(hit.viaLayoutSwap)
        }

        @Test
        fun `a swapped match ranks below the same match typed correctly`() {
            val idx = index(
                candidate("aaa_one", title = "ghbdsn digest"),
                candidate("bbb_two", title = "привіт digest"),
            )
            val hits = idx.search("ghbdsn")
            assertEquals(listOf("aaa_one", "bbb_two"), hits.map { it.channel.username })
            assertFalse(hits[0].viaLayoutSwap)
            assertTrue(hits[1].viaLayoutSwap)
        }

        @Test
        fun `alternates cover both Cyrillic layouts and skip identity`() {
            assertEquals(listOf("привіт", "привыт"), KeyboardLayouts.alternates("ghbdsn"))
            assertEquals(listOf("ghbdsn"), KeyboardLayouts.alternates("привіт"))
            assertEquals(emptyList<String>(), KeyboardLayouts.alternates("123"))
        }
    }

    @Nested
    inner class Ranking {
        private val idx = index(
            candidate("news", title = "Plain"),
            candidate("newsroom", title = "Room"),
            candidate("daily_brief", title = "News Daily"),
            candidate("worldwide", title = "Worldnews"),
            candidate("the_goodnews", title = "Good"),
            candidate("misc", title = "Misc", description = "All the latest news"),
            candidate("nothing", title = "Unrelated"),
        )

        @Test
        fun `tiers order exact, prefix, word prefix, title infix, username infix, description`() {
            val hits = idx.search("news")
            assertEquals(
                listOf("news", "newsroom", "daily_brief", "worldwide", "the_goodnews", "misc"),
                hits.map { it.channel.username },
            )
            assertEquals(
                listOf(
                    GuestMatchTier.ExactUsername,
                    GuestMatchTier.UsernamePrefix,
                    GuestMatchTier.TitleWordPrefix,
                    GuestMatchTier.TitleSubstring,
                    GuestMatchTier.UsernameSubstring,
                    GuestMatchTier.DescriptionSubstring,
                ),
                hits.map { it.tier },
            )
        }

        @Test
        fun `one letter only matches at a word start`() {
            val idx = index(candidate("abc_chan", title = "Zebra"), candidate("other", title = "Bazaar"))
            assertEquals(listOf("other"), idx.names("b"))
        }

        @Test
        fun `ties break by source weight, then title`() {
            val idx = index(
                candidate("m1", title = "Tech B", source = GuestChannelSource.Mentioned, count = 50),
                candidate("f1", title = "Tech C", source = GuestChannelSource.Forwarded, count = 2),
                candidate("c1", title = "Tech D", source = GuestChannelSource.Catalog),
                candidate("s1", title = "Tech E", source = GuestChannelSource.Subscribed),
                candidate("k1", title = "Tech F", source = GuestChannelSource.Known),
                candidate("m2", title = "Tech A", source = GuestChannelSource.Mentioned, count = 50),
            )
            assertEquals(listOf("s1", "k1", "c1", "f1", "m2", "m1"), idx.names("tech"))
        }

        @Test
        fun `results are capped`() {
            val idx = GuestChannelIndex.build((1..100).map { candidate("chan$it", title = "Channel $it") })
            assertEquals(GuestChannelIndex.DEFAULT_LIMIT, idx.search("channel").size)
            assertEquals(5, idx.search("channel", limit = 5).size)
        }

        @Test
        fun `blank query returns nothing`() {
            assertTrue(idx.search("   ").isEmpty())
            assertTrue(idx.search("@").isEmpty())
        }
    }

    @Nested
    inner class MultiToken {
        private val idx = index(
            candidate("kyiv_news", title = "Kyiv Independent News"),
            candidate("kyiv_food", title = "Kyiv Food"),
            candidate("lviv_news", title = "Lviv News"),
        )

        @Test
        fun `every token must match`() {
            assertEquals(listOf("kyiv_news"), idx.names("kyiv news"))
            assertEquals(listOf("kyiv_news"), idx.names("news kyiv"))
        }

        @Test
        fun `tokens may match across title and username`() {
            val idx = index(candidate("ukr_daily", title = "Morning Brief"))
            assertEquals(listOf("ukr_daily"), idx.names("morning ukr"))
        }

        @Test
        fun `phrase without breaks finds an underscored username`() {
            assertEquals(GuestMatchTier.ExactUsername, idx.search("kyivnews").first().tier)
            assertEquals(GuestMatchTier.ExactUsername, idx.search("kyiv_news").first().tier)
        }
    }

    @Nested
    inner class Merging {
        @Test
        fun `sources merge by lowercase username with the richest data`() {
            val idx = index(
                candidate("Durov", source = GuestChannelSource.Mentioned, count = 3),
                candidate("durov", title = "Forwarded name", source = GuestChannelSource.Forwarded, count = 2),
                candidate("@durov", title = "Durov", avatarUrl = "https://a/x.jpg", source = GuestChannelSource.Subscribed),
                candidate("DUROV", title = "Catalog title", description = "Editorial", source = GuestChannelSource.Catalog),
            )
            assertEquals(1, idx.size)
            val ch = idx["durov"]!!
            assertEquals("Durov", ch.title)
            assertEquals("https://a/x.jpg", ch.avatarUrl)
            assertEquals("Editorial", ch.description)
            assertTrue(ch.isSubscribed)
            assertEquals(2, ch.forwardCount)
            assertEquals(3, ch.mentionCount)
            assertTrue(ch.hasSource(GuestChannelSource.Catalog))
            assertFalse(ch.hasSource(GuestChannelSource.Known))
        }

        @Test
        fun `blank fields fall through to the next source`() {
            val idx = index(
                candidate("x_chan", title = "  ", source = GuestChannelSource.Known),
                candidate("x_chan", title = "Real title", source = GuestChannelSource.Forwarded),
            )
            assertEquals("Real title", idx["x_chan"]!!.title)
        }

        @Test
        fun `live card data outranks the catalog but not a followed row`() {
            val idx = index(
                candidate("a_chan", title = "Catalog A", source = GuestChannelSource.Catalog),
                candidate("a_chan", title = "Live A", source = GuestChannelSource.Live),
                candidate("b_chan", title = "Followed B", source = GuestChannelSource.Subscribed),
                candidate("b_chan", title = "Live B", source = GuestChannelSource.Live),
            )
            assertEquals("Live A", idx["a_chan"]!!.title)
            assertEquals("Followed B", idx["b_chan"]!!.title)
        }

        @Test
        fun `a title-less channel is still found by username`() {
            val idx = index(candidate("quiet_one", source = GuestChannelSource.Mentioned))
            assertEquals(GuestMatchTier.UsernamePrefix, idx.search("quiet").single().tier)
            assertNull(idx["quiet_one"]!!.title)
        }
    }

    @Nested
    inner class Ranges {
        @Test
        fun `title ranges point at the matched words in the original title`() {
            val title = "🔥 Kyiv Independent News"
            val idx = index(candidate("kin", title = title))
            val hit = idx.search("news kyiv").single()
            val parts = hit.titleRanges.map { title.substring(it.first, it.last + 1) }
            assertEquals(listOf("Kyiv", "News"), parts)
        }

        @Test
        fun `a phrase match is one range`() {
            val title = "Good Morning Kyiv"
            val idx = index(candidate("gm", title = title))
            val hit = idx.search("morning ky").single()
            assertEquals(listOf("Morning Ky"), hit.titleRanges.map { title.substring(it.first, it.last + 1) })
        }

        @Test
        fun `accented match maps to the accented chars`() {
            val title = "Café Crème"
            val hit = index(candidate("cc", title = title)).search("creme").single()
            assertEquals(listOf("Crème"), hit.titleRanges.map { title.substring(it.first, it.last + 1) })
        }

        @Test
        fun `layout-swapped match highlights the Cyrillic word`() {
            val title = "Ранкове привіт"
            val hit = index(candidate("hi_chan", title = title)).search("ghbdsn").single()
            assertEquals(listOf("привіт"), hit.titleRanges.map { title.substring(it.first, it.last + 1) })
        }

        @Test
        fun `username ranges for a username prefix`() {
            val hit = index(candidate("durov_says")).search("durov").single()
            assertEquals(listOf(0..4), hit.usernameRanges)
        }
    }

    @Nested
    inner class Probe {
        @Test
        fun `only username-shaped queries are probed`() {
            assertEquals("durov", probeableUsername("@Durov"))
            assertEquals("tech_news", probeableUsername("  tech_news "))
            assertNull(probeableUsername("abcd"))
            assertNull(probeableUsername("1durov"))
            assertNull(probeableUsername("tech news"))
            assertNull(probeableUsername("новини"))
            assertNull(probeableUsername("a".repeat(33)))
        }
    }

    @Nested
    inner class Bots {
        @Test
        fun `bot usernames end in bot, any case, plus Telegram's service bots`() {
            assertTrue(isBotUsername("humanbot"))
            assertTrue(isBotUsername("BotFather"))
            assertTrue(isBotUsername("@Stickers"))
            assertTrue(isBotUsername("PremiumBot"))
            assertTrue(isBotUsername("@richtextdemo_BOT"))
            assertFalse(isBotUsername("botanika"))
            assertFalse(isBotUsername("robotics_news"))
            assertFalse(isBotUsername("durov"))
        }
    }
}
