package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import dev.lyo.hortay.data.archive.db.SelectEntryMessages
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Why a message is in the archive browser. */
enum class ArchiveEntryStatus { Deleted, Edited }

/**
 * One message (an album counts as one) as the archive browser lists it. The browser depends
 * on this model only, never on the stored rows, so the storage schema can change under it.
 *
 * @property messageKey the message whose revision history the sheet opens — for an album
 *   deletion, its lowest-numbered member that holds a version.
 * @property eventAtMs deletion time, or the history position of the latest version.
 * @property versionCount versions the archive holds of [messageKey] (guest mode: plus the
 *   current one, which lives in the feed). Phantom duplicates are folded at the source
 *   ([dedupeVersions]), so stored versions are distinct ones.
 * @property textPreview text of the latest captured version (album: the first member with
 *   text); empty when there is none.
 * @property messageIds every archived message the entry stands for — what removing it purges.
 */
@Immutable
data class ArchiveEntry(
    val chat: ChatRef,
    val messageKey: String,
    val channelTitle: String?,
    val channelPhotoMinithumb: ByteArray?,
    val isComment: Boolean,
    val status: ArchiveEntryStatus,
    val eventAtMs: Long,
    val versionCount: Int,
    val textPreview: String,
    val mediaMinithumb: ByteArray?,
    val messageIds: ImmutableList<Long>,
) {
    /** Stable list key: one entry per (chat, message). */
    val key: String get() = "${chat.kind.name}:${chat.key}:$messageKey"

    override fun equals(other: Any?): Boolean =
        other is ArchiveEntry &&
            other.chat == chat &&
            other.messageKey == messageKey &&
            other.channelTitle == channelTitle &&
            other.channelPhotoMinithumb.contentEquals(channelPhotoMinithumb) &&
            other.isComment == isComment &&
            other.status == status &&
            other.eventAtMs == eventAtMs &&
            other.versionCount == versionCount &&
            other.textPreview == textPreview &&
            other.mediaMinithumb.contentEquals(mediaMinithumb) &&
            other.messageIds == messageIds

    override fun hashCode(): Int {
        var h = chat.hashCode()
        h = 31 * h + messageKey.hashCode()
        h = 31 * h + status.hashCode()
        h = 31 * h + eventAtMs.hashCode()
        h = 31 * h + versionCount
        h = 31 * h + textPreview.hashCode()
        return h
    }
}

/** Channel metadata the archive keeps per chat (ArchivedChannel row). */
@Immutable
data class ArchiveChannelInfo(val title: String, val photoMinithumb: ByteArray?)

/**
 * One archived message as [buildArchiveEntries] needs it — counts and previews computed
 * in SQL (`selectEntryMessages`), no blobs.
 *
 * @property latestAtMs history position of the latest version; 0 when it has none.
 * @property thumbVersionId the latest version carrying a minithumb, or null.
 */
internal data class ArchiveEntryRow(
    val messageId: Long,
    val chat: ChatRef,
    val messageKey: String,
    val albumKey: String?,
    val isComment: Boolean,
    val deletedAtMs: Long?,
    val versionCount: Int,
    val latestAtMs: Long,
    val textPreview: String,
    val thumbVersionId: Long?,
)

/** An entry before its minithumb is loaded: [thumbVersionId] names the version to take it from. */
internal data class ArchiveEntryDraft(val entry: ArchiveEntry, val thumbVersionId: Long?)

/**
 * Maps `selectEntryMessages` rows to entries: channel metadata from ArchivedChannel,
 * grouping by [buildArchiveEntries], then minithumbs for just the versions the entries show.
 * Blocking; [ArchiveRepository.observeEntries] runs it on the IO dispatcher.
 */
internal fun ArchiveDatabase.toArchiveEntries(rows: List<SelectEntryMessages>): ImmutableList<ArchiveEntry> {
    val light = rows.mapNotNull { r ->
        val source = runCatching { SourceKind.valueOf(r.source_kind) }.getOrNull() ?: return@mapNotNull null
        ArchiveEntryRow(
            messageId = r.id,
            chat = ChatRef(source, r.source_key),
            messageKey = r.message_key,
            albumKey = r.album_key,
            isComment = r.is_comment == 1L,
            deletedAtMs = r.deleted_at_ms,
            versionCount = r.version_count.toInt(),
            latestAtMs = r.latest_sort_at_ms,
            textPreview = r.latest_text,
            thumbVersionId = r.thumb_version_id.takeIf { it > 0L },
        )
    }
    val channels = archivedChannelQueries.selectAll().executeAsList().mapNotNull { c ->
        val source = runCatching { SourceKind.valueOf(c.source_kind) }.getOrNull() ?: return@mapNotNull null
        ChatRef(source, c.source_key) to ArchiveChannelInfo(c.title, c.photo_minithumb)
    }.toMap()
    val drafts = buildArchiveEntries(light, channels)
    val thumbs = HashMap<Long, ByteArray>()
    drafts.mapNotNull { it.thumbVersionId }.chunked(SQL_VARIABLE_CHUNK).forEach { ids ->
        messageVersionQueries.minithumbsByIds(ids).executeAsList().forEach { t ->
            t.media_minithumb?.let { thumbs[t.id] = it }
        }
    }
    return drafts.map { d ->
        val thumb = d.thumbVersionId?.let(thumbs::get)
        if (thumb == null) d.entry else d.entry.copy(mediaMinithumb = thumb)
    }.toImmutableList()
}

/**
 * Groups archived messages into browser entries, newest event first.
 *
 *  - A deleted message is one entry; the deleted messages of one album (same album key in
 *    the same chat) form a single entry, together with the album's members that were not
 *    deleted. Its preview text / thumbnail come from the first member, lowest key first,
 *    that has one. A deletion none of whose messages holds a version is not listed.
 *  - Any other message is an entry only when it holds at least two versions (guest mode:
 *    one, since guest captures store only the versions a change replaced). A message that
 *    never changed is never listed.
 */
internal fun buildArchiveEntries(
    rows: List<ArchiveEntryRow>,
    channels: Map<ChatRef, ArchiveChannelInfo>,
): List<ArchiveEntryDraft> {
    val byAlbum = rows.filter { it.albumKey != null }.groupBy { it.chat to it.albumKey!! }
    val claimed = HashSet<Long>()
    val out = mutableListOf<ArchiveEntryDraft>()

    for (deleted in rows.filter { it.deletedAtMs != null }.sortedByDescending { it.deletedAtMs }) {
        val members = deleted.albumKey?.let { byAlbum[deleted.chat to it] } ?: listOf(deleted)
        val ordered = members.filter { it.messageId !in claimed }.sortedWith(memberOrder)
        val withVersions = ordered.filter { it.versionCount > 0 }
        // Already folded into a later deletion of its album, or nothing archived to show.
        val anchor = withVersions.firstOrNull()?.takeIf { deleted.messageId !in claimed } ?: continue
        claimed += ordered.map { it.messageId }
        val deletedAt = ordered.mapNotNull { it.deletedAtMs }.max()
        out += ArchiveEntryDraft(
            entry = entryOf(
                anchor = anchor,
                channels = channels,
                isComment = deleted.isComment,
                status = ArchiveEntryStatus.Deleted,
                eventAtMs = deletedAt,
                versionCount = anchor.versionCount,
                textPreview = withVersions.firstOrNull { it.textPreview.isNotBlank() }?.textPreview.orEmpty(),
                messageIds = ordered.map { it.messageId }.sorted(),
            ),
            thumbVersionId = withVersions.firstNotNullOfOrNull { it.thumbVersionId },
        )
    }

    for (r in rows.filter { it.messageId !in claimed && it.deletedAtMs == null }) {
        // Guest mode stores only the versions a change replaced; the current one lives in
        // the feed, so one stored version already means one edit.
        val versionCount = if (r.chat.kind == SourceKind.WEB && r.versionCount > 0) r.versionCount + 1 else r.versionCount
        if (versionCount < 2) continue
        out += ArchiveEntryDraft(
            entry = entryOf(
                anchor = r,
                channels = channels,
                isComment = r.isComment,
                status = ArchiveEntryStatus.Edited,
                eventAtMs = r.latestAtMs,
                versionCount = versionCount,
                textPreview = r.textPreview,
                messageIds = listOf(r.messageId),
            ),
            thumbVersionId = r.thumbVersionId,
        )
    }
    out.sortWith(compareByDescending<ArchiveEntryDraft> { it.entry.eventAtMs }.thenBy { it.entry.key })
    return out
}

/** Album members by numeric message key (a key that is not a number sorts last), then text. */
private val memberOrder: Comparator<ArchiveEntryRow> =
    compareBy<ArchiveEntryRow>({ it.messageKey.toLongOrNull() ?: Long.MAX_VALUE }, { it.messageKey })

@Suppress("LongParameterList")
private fun entryOf(
    anchor: ArchiveEntryRow,
    channels: Map<ChatRef, ArchiveChannelInfo>,
    isComment: Boolean,
    status: ArchiveEntryStatus,
    eventAtMs: Long,
    versionCount: Int,
    textPreview: String,
    messageIds: List<Long>,
) = ArchiveEntry(
    chat = anchor.chat,
    messageKey = anchor.messageKey,
    channelTitle = channels[anchor.chat]?.title,
    channelPhotoMinithumb = channels[anchor.chat]?.photoMinithumb,
    isComment = isComment,
    status = status,
    eventAtMs = eventAtMs,
    versionCount = versionCount,
    textPreview = textPreview,
    mediaMinithumb = null,
    messageIds = messageIds.toImmutableList(),
)
