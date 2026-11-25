package dev.lyo.hortay.ui.archive

import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.archive.ArchivedContent
import dev.lyo.hortay.data.archive.ArchivedMediaRef
import dev.lyo.hortay.data.archive.ChatRef
import dev.lyo.hortay.data.archive.PostSnapshot
import dev.lyo.hortay.data.archive.SnapshotKind
import dev.lyo.hortay.data.archive.TdlibContentMeta
import dev.lyo.hortay.data.archive.VersionOrigin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

class RevisionHistoryTest {

    private var nextId = 1L

    private fun version(
        text: String,
        at: Long,
        entities: String = "[]",
        media: ArchivedMediaRef? = null,
        editedAt: Long? = at,
        unreadable: Boolean = false,
    ) = PostSnapshot(
        id = nextId++,
        chat = ChatRef.tdlib(1),
        messageKey = "10",
        albumKey = null,
        kind = SnapshotKind.VERSION,
        seenAtMs = at,
        editedAtMs = editedAt,
        content = ArchivedContent.Tdlib(
            TdlibContentMeta(text, entities, null, null, null, null, mediaRef = media),
            isUnreadable = unreadable,
        ),
        mediaMinithumb = null,
    )

    private fun deleted(at: Long) = PostSnapshot(
        id = nextId++, chat = ChatRef.tdlib(1), messageKey = "10", albumKey = null,
        kind = SnapshotKind.DELETED, seenAtMs = at, editedAtMs = null,
        content = ArchivedContent.Tdlib(TdlibContentMeta("", "[]", null, null, null, null)),
        mediaMinithumb = null,
    )

    private fun photo(uniqueId: String, sha: String? = null) =
        ArchivedMediaRef(type = "photo", width = 800, height = 600, remoteId = "r-$uniqueId", uniqueId = uniqueId, localArchiveSha = sha)

    @Test
    fun `identical consecutive versions collapse into the earliest`() {
        val entries = buildRevisionEntries(
            listOf(
                version("a", 1_000, editedAt = null, media = photo("p1")),
                version("b", 2_000, media = photo("p1")),
                version("b", 3_000, media = photo("p1", sha = "abc")),
                version("b", 4_000, media = photo("p1")),
                version("c", 5_000, media = photo("p1")),
            ),
        )
        assertEquals(listOf(1_000L, 2_000L, 5_000L), entries.map { it.snapshot.seenAtMs })
        // The collapsed run keeps the archive copy one of its captures got.
        assertEquals("abc", entries[1].media?.localArchiveSha)
        assertEquals(
            listOf(RevisionChange.Published, RevisionChange.Text, RevisionChange.Text),
            entries.map { it.change },
        )
    }

    @Test
    fun `the first version is Published only when stored as published`() {
        val published = buildRevisionEntries(listOf(version("a", 1_000).copy(origin = VersionOrigin.PUBLISHED)))
        val firstSeen = buildRevisionEntries(
            listOf(version("a", 1_000, editedAt = null).copy(origin = VersionOrigin.FIRST_SEEN_EDITED)),
        )
        val liveEdit = buildRevisionEntries(listOf(version("a", 1_000).copy(origin = VersionOrigin.LIVE_EDIT)))
        assertEquals(RevisionChange.Published, published.single().change)
        assertEquals(RevisionChange.FirstSeen, firstSeen.single().change)
        assertEquals(RevisionChange.FirstSeen, liveEdit.single().change)
    }

    @Test
    fun `same text with different entities is a formatting change, not a duplicate`() {
        val entries = buildRevisionEntries(
            listOf(
                version("hello", 1_000, editedAt = null),
                version("hello", 2_000, entities = """[{"offset":0,"length":5,"type":"TextEntityTypeBold"}]"""),
            ),
        )
        assertEquals(listOf(RevisionChange.Published, RevisionChange.Formatting), entries.map { it.change })
    }

    @Test
    fun `entity payload keys alone do not count as a change`() {
        val entries = buildRevisionEntries(
            listOf(
                version("go", 1_000, entities = """[{"offset":0,"length":2,"type":"TextEntityTypeTextUrl"}]"""),
                version("go", 2_000, entities = """[{"offset":0,"length":2,"type":"TextEntityTypeTextUrl","url":"https://a.b"}]"""),
            ),
        )
        assertEquals(1, entries.size)
    }

    @Test
    fun `media swap and text edit are classified`() {
        val entries = buildRevisionEntries(
            listOf(
                version("a", 1_000, editedAt = null, media = photo("p1")),
                version("a", 2_000, media = photo("p2")),
                version("b", 3_000, media = photo("p3")),
            ),
        )
        assertEquals(
            listOf(RevisionChange.Published, RevisionChange.Media, RevisionChange.TextAndMedia),
            entries.map { it.change },
        )
    }

    @Test
    fun `first row captured after an edit is not labelled as the publication`() {
        val entries = buildRevisionEntries(listOf(version("a", 1_000, editedAt = 900)))
        assertEquals(RevisionChange.FirstSeen, entries.single().change)
    }

    @Test
    fun `deleted markers collapse and keep the last readable version as base`() {
        val entries = buildRevisionEntries(listOf(version("a", 1_000, editedAt = null), deleted(2_000), deleted(3_000)))
        assertEquals(listOf(RevisionChange.Published, RevisionChange.Deleted), entries.map { it.change })
        assertEquals(entries[0], entries[1].previous)
    }

    @Test
    fun `unreadable rows stay as their own entry and are skipped as diff base`() {
        val entries = buildRevisionEntries(
            listOf(
                version("a", 1_000, editedAt = null),
                version("", 2_000, unreadable = true),
                version("b", 3_000),
            ),
        )
        assertEquals(
            listOf(RevisionChange.Published, RevisionChange.Unreadable, RevisionChange.Text),
            entries.map { it.change },
        )
        assertEquals("a", entries[2].previous?.text?.text)
    }

    @Test
    fun `legacy custom emoji without id keeps its glyph and gains emoji presentation`() {
        val ft = archivedFormattedText(
            "‼Title\nbody",
            """[{"offset":0,"length":1,"type":"TextEntityTypeCustomEmoji"},{"offset":0,"length":6,"type":"TextEntityTypeBold"}]""",
        )
        assertEquals("‼️Title\nbody", ft.text)
        val bold = ft.spans.single { it.style == FormattedText.Style.Bold }
        assertEquals(0, bold.start)
        assertEquals(7, bold.end)
    }

    @Test
    fun `stored payload restores links and custom emoji`() {
        val ft = archivedFormattedText(
            "‼ read",
            """[{"offset":0,"length":1,"type":"TextEntityTypeCustomEmoji","customEmojiId":42},""" +
                """{"offset":2,"length":4,"type":"TextEntityTypeTextUrl","url":"https://a.b"}]""",
        )
        assertEquals("‼ read", ft.text)
        assertTrue(ft.spans.any { it.style == FormattedText.Style.CustomEmoji(42) })
        assertTrue(ft.spans.any { it.style == FormattedText.Style.TextUrl("https://a.b") && it.start == 2 && it.end == 6 })
    }

    @Test
    fun `legacy masked link without url renders as plain text`() {
        val ft = archivedFormattedText("go", """[{"offset":0,"length":2,"type":"TextEntityTypeTextUrl"}]""")
        assertTrue(ft.spans.isEmpty())
    }

    @Test
    fun `diff keeps entities of both versions and marks the change`() {
        val old = FormattedText(
            "Big news today",
            listOf(FormattedText.Span(0, 3, FormattedText.Style.Bold), FormattedText.Span(9, 14, FormattedText.Style.Italic)),
        )
        val new = FormattedText("Big news tonight", listOf(FormattedText.Span(0, 3, FormattedText.Style.Bold)))
        val diff = composeDiff(old, new)
        assertEquals("Big news todaytonight", diff.text)
        assertTrue(diff.spans.any { it.style == FormattedText.Style.Bold && it.start == 0 && it.end == 3 })
        assertTrue(diff.spans.any { it.style == FormattedText.Style.Italic && it.start == 9 && it.end == 14 })
        assertTrue(diff.spans.any { it.style == FormattedText.Style.DiffRemoved && it.start == 9 && it.end == 14 })
        assertTrue(diff.spans.any { it.style == FormattedText.Style.DiffInserted && it.start == 14 && it.end == 21 })
    }

    private val tappable = setOf(
        FormattedText.Style.Url::class, FormattedText.Style.Mention::class, FormattedText.Style.Hashtag::class,
        FormattedText.Style.TextUrl::class, FormattedText.Style.MentionName::class,
    )

    private fun FormattedText.tappableSpans() = spans.filter { it.style::class in tappable }

    private fun FormattedText.removedRanges() =
        spans.filter { it.style == FormattedText.Style.DiffRemoved }.map { it.start until it.end }

    /** No tap target covers a character of a removed fragment. */
    private fun assertNoTappableOverRemoved(diff: FormattedText) {
        val removed = diff.removedRanges()
        for (span in diff.tappableSpans()) {
            for (r in removed) {
                assertTrue(span.end <= r.first || span.start > r.last, "$span overlaps removed $r in '${diff.text}'")
            }
        }
    }

    @Test
    fun `an edited url opens the new target, not a splice of both`() {
        val old = FormattedText("https://a.com", listOf(FormattedText.Span(0, 13, FormattedText.Style.Url)))
        val new = FormattedText("https://b.com", listOf(FormattedText.Span(0, 13, FormattedText.Style.Url)))
        val diff = composeDiff(old, new)

        assertEquals("https://ab.com", diff.text)
        assertNoTappableOverRemoved(diff)
        val links = diff.tappableSpans()
        assertTrue(links.isNotEmpty())
        assertTrue(links.all { it.style == FormattedText.Style.TextUrl("https://b.com") }, "$links")
    }

    @Test
    fun `an edited mention opens the new handle`() {
        val old = FormattedText("hi @alice", listOf(FormattedText.Span(3, 9, FormattedText.Style.Mention)))
        val new = FormattedText("hi @bob", listOf(FormattedText.Span(3, 7, FormattedText.Style.Mention)))
        val diff = composeDiff(old, new)

        assertEquals("hi @alicebob", diff.text)
        assertNoTappableOverRemoved(diff)
        val links = diff.tappableSpans()
        assertTrue(links.isNotEmpty())
        assertTrue(links.all { it.style == FormattedText.Style.TextUrl("https://t.me/bob") }, "$links")
    }

    @Test
    fun `an edited hashtag split by a removed fragment is not tappable`() {
        val old = FormattedText("#foo", listOf(FormattedText.Span(0, 4, FormattedText.Style.Hashtag)))
        val new = FormattedText("#bar", listOf(FormattedText.Span(0, 4, FormattedText.Style.Hashtag)))
        val diff = composeDiff(old, new)

        assertEquals("#foobar", diff.text)
        assertTrue(diff.tappableSpans().isEmpty(), "${diff.spans}")
    }

    @Test
    fun `a removed link keeps its look but is not tappable`() {
        val old = FormattedText(
            "read https://a.com now",
            listOf(
                FormattedText.Span(5, 18, FormattedText.Style.Url),
                FormattedText.Span(5, 18, FormattedText.Style.Bold),
            ),
        )
        val new = FormattedText("read now", emptyList())
        val diff = composeDiff(old, new)

        assertTrue(diff.tappableSpans().isEmpty(), "${diff.spans}")
        assertTrue(diff.spans.any { it.style == FormattedText.Style.Bold })
    }

    @Test
    fun `a masked link split by a removed fragment keeps its target on the new text only`() {
        val style = FormattedText.Style.TextUrl("https://example.com")
        val old = FormattedText("open old page", listOf(FormattedText.Span(0, 13, style)))
        val new = FormattedText("open new page", listOf(FormattedText.Span(0, 13, style)))
        val diff = composeDiff(old, new)

        assertNoTappableOverRemoved(diff)
        assertTrue(diff.tappableSpans().all { it.style == style })
        assertTrue(diff.tappableSpans().size >= 2)
    }

    @Test
    fun `an untouched link stays a plain url`() {
        val old = FormattedText("see https://a.com", listOf(FormattedText.Span(4, 17, FormattedText.Style.Url)))
        val new = FormattedText("look https://a.com", listOf(FormattedText.Span(5, 18, FormattedText.Style.Url)))
        val diff = composeDiff(old, new)

        val link = diff.tappableSpans().single()
        assertEquals(FormattedText.Style.Url, link.style)
        assertEquals("https://a.com", diff.text.substring(link.start, link.end))
    }

    @Test
    fun `unchanged text has no diff marks`() {
        val ft = FormattedText("same", emptyList())
        assertTrue(composeDiff(ft, ft).spans.isEmpty())
    }

    @Test
    fun `labels add seconds only to colliding minutes and dates across days`() {
        val utc = TimeZone.getTimeZone("UTC")
        val base = 1_700_000_000_000L - 1_700_000_000_000L % 3_600_000L // on the hour
        val sameDay = revisionLabelPrecisions(listOf(base, base + 120_000, base + 125_000), utc)
        assertEquals(
            listOf(RevisionLabelPrecision.Time, RevisionLabelPrecision.TimeSeconds, RevisionLabelPrecision.TimeSeconds),
            sameDay,
        )
        val twoDays = revisionLabelPrecisions(listOf(base, base + 86_400_000L), utc)
        assertEquals(listOf(RevisionLabelPrecision.DateTime, RevisionLabelPrecision.DateTime), twoDays)
    }
}
