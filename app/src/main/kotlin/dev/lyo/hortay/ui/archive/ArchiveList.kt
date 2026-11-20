package dev.lyo.hortay.ui.archive

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.archive.ArchiveEntry
import dev.lyo.hortay.data.archive.ArchiveEntryStatus
import dev.lyo.hortay.data.archive.ArchiveScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.util.Calendar
import java.util.TimeZone

/** What the archive browser shows: a status chip, the posts / comments scope and a search query. */
@Immutable
data class ArchiveListFilter(
    val status: ArchiveEntryStatus? = null,
    val scope: ArchiveScope = ArchiveScope.ALL,
    val query: String = "",
)

/** Chip counts, after scope and search but before the status chip. */
@Immutable
data class ArchiveCounts(val all: Int, val deleted: Int, val edited: Int) {
    companion object {
        val Empty = ArchiveCounts(0, 0, 0)
    }
}

/** One row of the archive browser's lazy list. */
@Immutable
sealed interface ArchiveListItem {
    val key: String

    /** Section header for the local day starting at [dayStartMs]. */
    @Immutable
    data class DayHeader(val dayStartMs: Long) : ArchiveListItem {
        override val key: String get() = "day:$dayStartMs"
    }

    @Immutable
    data class Entry(val entry: ArchiveEntry) : ArchiveListItem {
        override val key: String get() = entry.key
    }
}

/**
 * @property archiveEmpty nothing at all is listable, whatever the filter.
 * @property hasComments some entry is a comment, so the posts / comments scope is worth offering.
 */
@Immutable
data class ArchiveListState(
    val enabled: Boolean,
    val archiveEmpty: Boolean,
    val hasComments: Boolean,
    val counts: ArchiveCounts,
    val items: ImmutableList<ArchiveListItem>,
) {
    companion object {
        val Empty = ArchiveListState(false, true, false, ArchiveCounts.Empty, persistentListOf())
    }
}

/** Applies scope and search (case-insensitive, over channel title and text preview). */
fun filterArchiveEntries(entries: List<ArchiveEntry>, scope: ArchiveScope, query: String): List<ArchiveEntry> {
    val q = query.trim()
    return entries.filter { e ->
        val inScope = when (scope) {
            ArchiveScope.ALL -> true
            ArchiveScope.POSTS -> !e.isComment
            ArchiveScope.COMMENTS -> e.isComment
        }
        inScope && (
            q.isEmpty() ||
                e.textPreview.contains(q, ignoreCase = true) ||
                e.channelTitle?.contains(q, ignoreCase = true) == true
            )
    }
}

fun archiveCounts(entries: List<ArchiveEntry>): ArchiveCounts = ArchiveCounts(
    all = entries.size,
    deleted = entries.count { it.status == ArchiveEntryStatus.Deleted },
    edited = entries.count { it.status == ArchiveEntryStatus.Edited },
)

/** Interleaves a [ArchiveListItem.DayHeader] before the first entry of each local day. */
fun buildArchiveListItems(entries: List<ArchiveEntry>, zone: TimeZone): ImmutableList<ArchiveListItem> {
    val cal = Calendar.getInstance(zone)
    val out = ArrayList<ArchiveListItem>(entries.size + entries.size / 4 + 1)
    var lastDay = Long.MIN_VALUE
    for (e in entries) {
        val day = startOfDay(cal, e.eventAtMs)
        if (day != lastDay) {
            out += ArchiveListItem.DayHeader(day)
            lastDay = day
        }
        out += ArchiveListItem.Entry(e)
    }
    return out.toImmutableList()
}

/** Builds the browser state from the repository's entries and the current filter. */
fun buildArchiveListState(
    entries: List<ArchiveEntry>,
    filter: ArchiveListFilter,
    enabled: Boolean,
    zone: TimeZone,
): ArchiveListState {
    val scoped = filterArchiveEntries(entries, filter.scope, filter.query)
    val shown = filter.status?.let { s -> scoped.filter { it.status == s } } ?: scoped
    return ArchiveListState(
        enabled = enabled,
        archiveEmpty = entries.isEmpty(),
        hasComments = entries.any { it.isComment },
        counts = archiveCounts(scoped),
        items = buildArchiveListItems(shown, zone),
    )
}

internal fun startOfDay(cal: Calendar, epochMs: Long): Long {
    cal.timeInMillis = epochMs
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** A chat's live avatar for an archive row: TDLib's small photo file plus its minithumb. */
@Immutable
data class ArchiveAvatar(val fileId: Int?, val minithumb: ByteArray?) {
    override fun equals(other: Any?): Boolean =
        other is ArchiveAvatar && other.fileId == fileId && other.minithumb.contentEquals(minithumb)

    override fun hashCode(): Int = 31 * (fileId ?: 0) + (minithumb?.contentHashCode() ?: 0)
}
