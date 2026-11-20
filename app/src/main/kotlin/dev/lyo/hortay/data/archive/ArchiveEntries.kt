package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import dev.lyo.hortay.data.archive.db.SelectArchiveEntryRows
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Why a message is in the archive browser. */
enum class ArchiveEntryStatus { Deleted, Edited }

/**
 * One message (an album counts as one) as the archive browser lists it. The browser depends
 * on this model only, never on [PostSnapshot] rows, so the storage schema can change under it.
 *
 * @property messageKey the message whose revision history the sheet opens — for an album
 *   deletion, the album's anchor (the key the DELETED row was written under).
 * @property eventAtMs deletion time, or the time the latest version appeared.
 * @property versionCount distinct versions the archive holds: consecutive captures with the
 *   same content hash (re-derived with the current normalizer, see [canonicalHashes]) count
 *   once, the way the revision sheet folds identical snapshots.
 * @property textPreview text of the latest captured version (album: the first member with
 *   text); empty when there is none.
 * @property snapshotIds every stored row of the entry's messages — what removing it purges.
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
    val snapshotIds: ImmutableList<Long>,
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
            other.snapshotIds == snapshotIds

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
 * A stored row as [buildArchiveEntries] needs it — the light columns of `PostSnapshot`
 * (no blob, no minithumb bytes).
 */
internal data class ArchiveEntryRow(
    val id: Long,
    val chat: ChatRef,
    val messageKey: String,
    val albumKey: String?,
    val kind: SnapshotKind,
    val seenAtMs: Long,
    val contentHash: String,
    val textPreview: String,
    val deletedMessageKeys: List<String>,
    val isComment: Boolean,
    val hasThumb: Boolean,
)

/** An entry before its minithumbs are loaded: [thumbRowId] names the row to take one from. */
internal data class ArchiveEntryDraft(val entry: ArchiveEntry, val thumbRowId: Long?)

/** Ids per `IN (?)` batch: well under SQLite's 999-variable limit on Android 8. */
internal const val SQL_VARIABLE_CHUNK = 500

internal fun decodeDeletedKeys(json: String?): List<String> =
    json?.let { runCatching { Json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
        ?: emptyList()

/**
 * Maps `selectArchiveEntryRows` rows to entries: channel metadata from ArchivedChannel,
 * grouping by [buildArchiveEntries], then minithumbs for just the rows the entries show.
 * Blocking; [ArchiveRepository.observeEntries] runs it on the IO dispatcher.
 */
internal fun ArchiveDatabase.toArchiveEntries(rows: List<SelectArchiveEntryRows>): ImmutableList<ArchiveEntry> {
    val light = rows.mapNotNull { r ->
        val source = runCatching { SourceKind.valueOf(r.source_kind) }.getOrNull() ?: return@mapNotNull null
        val kind = runCatching { SnapshotKind.valueOf(r.kind) }.getOrNull() ?: return@mapNotNull null
        ArchiveEntryRow(
            id = r.id,
            chat = ChatRef(source, r.source_key),
            messageKey = r.message_key,
            albumKey = r.album_key,
            kind = kind,
            seenAtMs = r.seen_at_ms,
            contentHash = r.content_hash,
            textPreview = r.text_preview,
            deletedMessageKeys = decodeDeletedKeys(r.deleted_msg_keys),
            isComment = r.is_comment == 1L,
            hasThumb = r.has_thumb == 1L,
        )
    }
    val rehashed = canonicalHashes(light)
    val rows = if (rehashed.isEmpty()) light else light.map { r -> rehashed[r.id]?.let { r.copy(contentHash = it) } ?: r }
    val channels = archivedChannelQueries.selectAll().executeAsList().mapNotNull { c ->
        val source = runCatching { SourceKind.valueOf(c.source_kind) }.getOrNull() ?: return@mapNotNull null
        ChatRef(source, c.source_key) to ArchiveChannelInfo(c.title, c.photo_minithumb)
    }.toMap()
    val drafts = buildArchiveEntries(rows, channels)
    val thumbs = HashMap<Long, ByteArray>()
    drafts.mapNotNull { it.thumbRowId }.chunked(SQL_VARIABLE_CHUNK).forEach { ids ->
        postSnapshotQueries.selectMinithumbsByIds(ids).executeAsList().forEach { t ->
            t.media_minithumb?.let { thumbs[t.id] = it }
        }
    }
    return drafts.map { d ->
        val thumb = d.thumbRowId?.let(thumbs::get)
        if (thumb == null) d.entry else d.entry.copy(mediaMinithumb = thumb)
    }.toImmutableList()
}

/**
 * Hashes re-derived with the current [ContentNormalizer] for the TDLib VERSION rows of
 * every message whose stored hashes differ — by row id. Rows stored before a normalizer
 * change keep the hash of the old projection, so two captures of one unchanged version
 * can carry different stored hashes; counting those as two versions would list a post
 * the reader can't tell apart from its "previous version". Only messages with more than
 * one stored hash are read (blob per row), and a row that can't be decoded keeps its
 * stored hash.
 */
private fun ArchiveDatabase.canonicalHashes(rows: List<ArchiveEntryRow>): Map<Long, String> {
    val ids = rows.asSequence()
        .filter { it.kind == SnapshotKind.VERSION && it.chat.kind == SourceKind.TDLIB }
        .groupBy { it.chat to it.messageKey }
        .values
        .filter { versions -> versions.mapTo(HashSet()) { it.contentHash }.size > 1 }
        .flatMap { versions -> versions.map { it.id } }
    val out = HashMap<Long, String>()
    ids.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
        postSnapshotQueries.selectContentByIds(chunk).executeAsList().forEach { row ->
            decodeTdlibOrNull(row.content_kind, row.content_blob)?.let { out[row.id] = ContentNormalizer.canonicalHash(it) }
        }
    }
    return out
}

private data class MessageId(val chat: ChatRef, val messageKey: String)

/**
 * Groups stored rows into browser entries, newest event first.
 *
 *  - A deletion is one entry anchored at its DELETED row's message. The album members it
 *    names (`deleted_msg_keys`, or the same album key in the same chat) fold into it: their
 *    rows are purged with it and supply the preview when the anchor has no text or media.
 *    A deletion none of whose messages has a stored version is not listed.
 *  - Any other message is an entry only when it holds at least two distinct versions
 *    (guest-mode messages: at least one, since guest captures store only replaced versions).
 *    A baseline-only message is never listed.
 *
 * [rows] may come in any order; they are sorted by (seenAtMs, id) here.
 */
internal fun buildArchiveEntries(
    rows: List<ArchiveEntryRow>,
    channels: Map<ChatRef, ArchiveChannelInfo>,
): List<ArchiveEntryDraft> {
    val byMessage = LinkedHashMap<MessageId, MutableList<ArchiveEntryRow>>()
    for (r in rows.sortedWith(compareBy(ArchiveEntryRow::seenAtMs, ArchiveEntryRow::id))) {
        byMessage.getOrPut(MessageId(r.chat, r.messageKey)) { mutableListOf() } += r
    }
    val byAlbum = HashMap<Pair<ChatRef, String>, MutableList<MessageId>>()
    for ((id, list) in byMessage) {
        val album = list.lastOrNull { it.kind == SnapshotKind.VERSION && it.albumKey != null }?.albumKey
            ?: list.lastOrNull { it.albumKey != null }?.albumKey
            ?: continue
        byAlbum.getOrPut(id.chat to album) { mutableListOf() } += id
    }

    val claimed = HashSet<MessageId>()
    val out = mutableListOf<ArchiveEntryDraft>()

    // Deletions, newest first, so a message named by two deletions joins the later one.
    val deletions = byMessage.mapNotNull { (id, list) ->
        list.lastOrNull { it.kind == SnapshotKind.DELETED }?.let { id to it }
    }.sortedByDescending { it.second.seenAtMs }
    for ((anchor, deletedRow) in deletions) {
        if (anchor in claimed) continue
        deletionDraft(anchor, deletedRow, byMessage, byAlbum, claimed, channels)?.let(out::add)
    }

    for ((id, list) in byMessage.filterKeys { it !in claimed }) {
        val runs = versionRuns(list)
        val isWeb = id.chat.kind == SourceKind.WEB
        // Guest mode stores only the versions a change replaced; the current one lives in
        // the feed, so one stored version already means one edit.
        val versionCount = if (isWeb && runs.isNotEmpty()) runs.size + 1 else runs.size
        if (versionCount < 2) continue
        val versions = list.filter { it.kind == SnapshotKind.VERSION }
        val textRow = versions.lastOrNull { it.textPreview.isNotBlank() }
        val thumbRow = versions.lastOrNull { it.hasThumb }
        out += ArchiveEntryDraft(
            entry = ArchiveEntry(
                chat = id.chat,
                messageKey = id.messageKey,
                channelTitle = channels[id.chat]?.title,
                channelPhotoMinithumb = channels[id.chat]?.photoMinithumb,
                isComment = list.any { it.isComment },
                status = ArchiveEntryStatus.Edited,
                eventAtMs = runs.last().seenAtMs,
                versionCount = versionCount,
                textPreview = textRow?.textPreview.orEmpty(),
                mediaMinithumb = null,
                snapshotIds = list.map { it.id }.toImmutableList(),
            ),
            thumbRowId = thumbRow?.id,
        )
    }
    out.sortWith(compareByDescending<ArchiveEntryDraft> { it.entry.eventAtMs }.thenBy { it.entry.key })
    return out
}

/**
 * The entry of one deletion anchored at [anchor], claiming its members in [claimed]; null,
 * claiming nothing, when none of its messages has a stored version.
 */
@Suppress("LongParameterList")
private fun deletionDraft(
    anchor: MessageId,
    deletedRow: ArchiveEntryRow,
    byMessage: Map<MessageId, List<ArchiveEntryRow>>,
    byAlbum: Map<Pair<ChatRef, String>, List<MessageId>>,
    claimed: MutableSet<MessageId>,
    channels: Map<ChatRef, ArchiveChannelInfo>,
): ArchiveEntryDraft? {
    val members = LinkedHashSet<MessageId>()
    members += anchor
    deletedRow.deletedMessageKeys.forEach { members += MessageId(anchor.chat, it) }
    deletedRow.albumKey?.let { album -> byAlbum[anchor.chat to album]?.let(members::addAll) }
    members.removeAll { it != anchor && it in claimed }

    val memberRows = members.mapNotNull { byMessage[it] }
    // Anchor first, then the other members: the anchor's own content is what the sheet shows.
    val versionsByPreference = memberRows.map { list -> list.filter { it.kind == SnapshotKind.VERSION } }
    // A deletion of a message the archive never held carries nothing to show. Capture no
    // longer writes these and the nightly sweep drops old ones; skip any still stored.
    if (versionsByPreference.all { it.isEmpty() }) return null
    claimed += members
    val textRow = versionsByPreference.firstNotNullOfOrNull { v -> v.lastOrNull { it.textPreview.isNotBlank() } }
    val thumbRow = versionsByPreference.firstNotNullOfOrNull { v -> v.lastOrNull { it.hasThumb } }
    val anchorRows = byMessage[anchor].orEmpty()
    return ArchiveEntryDraft(
        entry = ArchiveEntry(
            chat = anchor.chat,
            messageKey = anchor.messageKey,
            channelTitle = channels[anchor.chat]?.title,
            channelPhotoMinithumb = channels[anchor.chat]?.photoMinithumb,
            isComment = deletedRow.isComment,
            status = ArchiveEntryStatus.Deleted,
            eventAtMs = deletedRow.seenAtMs,
            versionCount = versionRuns(anchorRows).size,
            textPreview = textRow?.textPreview.orEmpty(),
            mediaMinithumb = null,
            snapshotIds = memberRows.flatten().map { it.id }.sorted().toImmutableList(),
        ),
        thumbRowId = thumbRow?.id,
    )
}

/**
 * First row of each run of consecutive VERSION rows sharing a content hash, in time order —
 * one per version the reader can tell apart. A DELETED row between two versions breaks a run.
 */
private fun versionRuns(rowsAsc: List<ArchiveEntryRow>): List<ArchiveEntryRow> {
    val runs = mutableListOf<ArchiveEntryRow>()
    var lastHash: String? = null
    for (r in rowsAsc) {
        if (r.kind != SnapshotKind.VERSION) {
            lastHash = null
            continue
        }
        if (r.contentHash != lastHash) runs += r
        lastHash = r.contentHash
    }
    return runs
}
