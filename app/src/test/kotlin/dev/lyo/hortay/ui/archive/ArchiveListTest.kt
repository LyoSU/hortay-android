package dev.lyo.hortay.ui.archive

import dev.lyo.hortay.data.archive.ArchiveEntry
import dev.lyo.hortay.data.archive.ArchiveEntryStatus
import dev.lyo.hortay.data.archive.ArchiveScope
import dev.lyo.hortay.data.archive.ChatRef
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

class ArchiveListTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 24L * 60 * 60 * 1000

    private fun entry(
        key: String,
        status: ArchiveEntryStatus,
        at: Long,
        text: String = "",
        title: String? = "News",
        comment: Boolean = false,
    ) = ArchiveEntry(
        chat = ChatRef.tdlib(1), messageKey = key, channelTitle = title, channelPhotoMinithumb = null,
        isComment = comment, status = status, eventAtMs = at, versionCount = 2, textPreview = text,
        mediaMinithumb = null, snapshotIds = persistentListOf(),
    )

    @Test
    fun dayHeadersPrecedeTheFirstEntryOfEachDay() {
        val items = buildArchiveListItems(
            listOf(
                entry("a", ArchiveEntryStatus.Deleted, 3 * day + 5_000),
                entry("b", ArchiveEntryStatus.Edited, 3 * day + 1_000),
                entry("c", ArchiveEntryStatus.Edited, day + 1_000),
            ),
            utc,
        )
        assertEquals(
            listOf("day:${3 * day}", "TDLIB:1:a", "TDLIB:1:b", "day:$day", "TDLIB:1:c"),
            items.map { it.key },
        )
    }

    @Test
    fun countsFollowScopeAndSearchButNotTheStatusChip() {
        val entries = listOf(
            entry("a", ArchiveEntryStatus.Deleted, 3, text = "Budget vote"),
            entry("b", ArchiveEntryStatus.Edited, 2, text = "budget leak"),
            entry("c", ArchiveEntryStatus.Edited, 1, text = "weather", comment = true),
        )
        val state = buildArchiveListState(
            entries,
            ArchiveListFilter(status = ArchiveEntryStatus.Deleted, query = "BUDGET"),
            enabled = true,
            zone = utc,
        )
        assertEquals(ArchiveCounts(all = 2, deleted = 1, edited = 1), state.counts)
        assertEquals(listOf("TDLIB:1:a"), state.items.filterIsInstance<ArchiveListItem.Entry>().map { it.key })
        assertTrue(state.hasComments)
        assertFalse(state.archiveEmpty)
    }

    @Test
    fun scopeAndChannelTitleSearch() {
        val entries = listOf(
            entry("a", ArchiveEntryStatus.Deleted, 3, title = "Kyiv Daily"),
            entry("b", ArchiveEntryStatus.Edited, 2, title = "Other", comment = true),
        )
        assertEquals(listOf("a"), filterArchiveEntries(entries, ArchiveScope.POSTS, "").map { it.messageKey })
        assertEquals(listOf("b"), filterArchiveEntries(entries, ArchiveScope.COMMENTS, "").map { it.messageKey })
        assertEquals(listOf("a"), filterArchiveEntries(entries, ArchiveScope.ALL, "kyiv").map { it.messageKey })
    }

    @Test
    fun emptyArchiveIsReportedEvenWithAFilter() {
        val state = buildArchiveListState(emptyList(), ArchiveListFilter(query = "x"), enabled = false, zone = utc)
        assertTrue(state.archiveEmpty)
        assertFalse(state.enabled)
    }
}
